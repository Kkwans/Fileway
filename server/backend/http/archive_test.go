package fbhttp

import (
	"archive/tar"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/archivefs"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestArchiveExtractionUsesOneCancelableGlobalWorkerSlot(t *testing.T) {
	if capacity := cap(archiveExtractionSlot); capacity != 1 {
		t.Fatalf("archive extraction worker capacity = %d", capacity)
	}
	archiveExtractionSlot <- struct{}{}
	defer func() { <-archiveExtractionSlot }()

	ctx, cancel := context.WithCancel(context.Background())
	d := &data{user: &users.User{Fs: afero.NewMemMapFs()}}
	runner := archiveExtractRunner(d, &tasks.Task{}, archiveExtractTaskArgs{})
	finished := make(chan error, 1)
	go func() {
		_, err := runner(ctx, func(tasks.Progress) error { return nil })
		finished <- err
	}()
	select {
	case err := <-finished:
		t.Fatalf("queued extraction returned before slot release: %v", err)
	case <-time.After(25 * time.Millisecond):
	}
	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("queued cancellation error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("queued extraction did not honor cancellation")
	}
}

func TestArchiveHTTPListsExtractsAndKeepsResultsPrivate(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "owner", Perm: users.Permissions{Download: true, Create: true}},
		users.User{Username: "other", Perm: users.Permissions{Download: true, Create: true}},
	)
	owner := trashHTTPUserByName(t, h, "owner")
	other := trashHTTPUserByName(t, h, "other")
	filesystem := h.fs[owner.ID]
	writeTarFixture(t, filesystem, "/bundle.tar", map[string]string{
		"folder/hello.txt": "hello",
		"skip.txt":         "skip",
	})
	if err := filesystem.MkdirAll("/destination", 0o750); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, archiveEntriesHandler, http.MethodGet, "/archives/entries?path=/bundle.tar", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("entries status = %d body=%s", response.Code, response.Body.String())
	}
	var listing archivefs.Listing
	if err := json.Unmarshal(response.Body.Bytes(), &listing); err != nil {
		t.Fatal(err)
	}
	if listing.Format != "tar" || len(listing.Entries) != 2 || listing.Truncated {
		t.Fatalf("listing = %#v", listing)
	}

	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	response = h.request(t, owner.ID, archiveExtractStartHandler(runtime), http.MethodPost, "/archives/extractions", bytes.NewBufferString(`{
		"archivePath":"/bundle.tar",
		"destination":"/destination",
		"selected":["folder"]
	}`), nil)
	if response.Code != http.StatusAccepted {
		t.Fatalf("extract status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "archivePath") || strings.Contains(response.Body.String(), "selected") {
		t.Fatalf("task response leaked internal args: %s", response.Body.String())
	}
	var created tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	completed := waitForHTTPTask(t, h, created.ID, tasks.StatusCompleted)
	if completed.Type != tasks.TypeArchiveExtract || len(completed.Result) == 0 {
		t.Fatalf("completed extraction task = %#v", completed)
	}
	content, err := afero.ReadFile(filesystem, "/destination/folder/hello.txt")
	if err != nil || string(content) != "hello" {
		t.Fatalf("extracted content=%q err=%v", content, err)
	}
	if exists, _ := afero.Exists(filesystem, "/destination/skip.txt"); exists {
		t.Fatal("unselected file was extracted")
	}

	response = h.request(t, owner.ID, archiveExtractResultHandler, http.MethodGet, "/archives/extractions/"+created.ID, nil, map[string]string{"id": created.ID})
	if response.Code != http.StatusOK {
		t.Fatalf("result status = %d body=%s", response.Code, response.Body.String())
	}
	var report archivefs.ExtractReport
	if err := json.Unmarshal(response.Body.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if report.ExtractedFiles != 1 || report.ExtractedBytes != 5 || report.Destination != "/destination" {
		t.Fatalf("extract report = %#v", report)
	}

	privateResponse := h.request(t, other.ID, archiveExtractResultHandler, http.MethodGet, "/archives/extractions/"+created.ID, nil, map[string]string{"id": created.ID})
	if privateResponse.Code != http.StatusForbidden {
		t.Fatalf("cross-user result status = %d body=%s", privateResponse.Code, privateResponse.Body.String())
	}
}

func TestArchiveHTTPRequiresPermissionsAndSupportedSafePaths(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "reader", Perm: users.Permissions{Download: true}},
		users.User{Username: "limited"},
		users.User{Username: "hidden", HideDotfiles: true, Perm: users.Permissions{Download: true, Create: true}},
	)
	reader := trashHTTPUserByName(t, h, "reader")
	limited := trashHTTPUserByName(t, h, "limited")
	hidden := trashHTTPUserByName(t, h, "hidden")
	writeTarFixture(t, h.fs[reader.ID], "/bundle.tar", map[string]string{"file.txt": "data"})
	if err := h.fs[reader.ID].MkdirAll("/destination", 0o750); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, limited.ID, archiveEntriesHandler, http.MethodGet, "/archives/entries?path=/bundle.tar", nil, nil)
	if response.Code != http.StatusForbidden {
		t.Fatalf("read permission status = %d body=%s", response.Code, response.Body.String())
	}
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	response = h.request(t, reader.ID, archiveExtractStartHandler(runtime), http.MethodPost, "/archives/extractions", bytes.NewBufferString(`{
		"archivePath":"/bundle.tar","destination":"/destination","selected":["file.txt"]
	}`), nil)
	if response.Code != http.StatusForbidden {
		t.Fatalf("create permission status = %d body=%s", response.Code, response.Body.String())
	}

	if err := h.fs[hidden.ID].MkdirAll("/.hidden", 0o750); err != nil {
		t.Fatal(err)
	}
	writeTarFixture(t, h.fs[hidden.ID], "/.hidden/bundle.tar", map[string]string{"file.txt": "data"})
	response = h.request(t, hidden.ID, archiveEntriesHandler, http.MethodGet, "/archives/entries?path=/.hidden/bundle.tar", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("direct hidden archive status = %d body=%s", response.Code, response.Body.String())
	}

	if err := afero.WriteFile(h.fs[hidden.ID], "/unsupported.iso", []byte("not-a-supported-archive"), 0o640); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, hidden.ID, archiveEntriesHandler, http.MethodGet, "/archives/entries?path=/unsupported.iso", nil, nil)
	if response.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("unsupported archive status = %d body=%s", response.Code, response.Body.String())
	}

	if err := afero.WriteFile(h.fs[hidden.ID], "/broken.zip", []byte("not-a-zip"), 0o640); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, hidden.ID, archiveEntriesHandler, http.MethodGet, "/archives/entries?path=/broken.zip", nil, nil)
	if response.Code != http.StatusUnprocessableEntity {
		t.Fatalf("broken archive status = %d body=%s", response.Code, response.Body.String())
	}
}

func writeTarFixture(t *testing.T, filesystem afero.Fs, archivePath string, contents map[string]string) {
	t.Helper()
	var buffer bytes.Buffer
	writer := tar.NewWriter(&buffer)
	for name, content := range contents {
		header := &tar.Header{
			Name: name, Mode: 0o640, Size: int64(len(content)),
			ModTime: time.Unix(1_700_000_000, 0), Typeflag: tar.TypeReg,
		}
		if err := writer.WriteHeader(header); err != nil {
			t.Fatal(err)
		}
		if _, err := writer.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(filesystem, archivePath, buffer.Bytes(), 0o640); err != nil {
		t.Fatal(err)
	}
}
