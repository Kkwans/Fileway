package archivefs

import (
	"archive/zip"
	"context"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"testing"
	"testing/fstest"
	"time"

	"github.com/mholt/archives"
	"github.com/spf13/afero"
)

func TestSupportedFormatsListAndExtractSelectedEntries(t *testing.T) {
	formats := []struct {
		name   string
		format archives.Archiver
	}{
		{name: "sample.zip", format: archives.Zip{}},
		{name: "sample.tar", format: archives.Tar{}},
		{name: "sample.tar.gz", format: compressedTar(archives.Gz{})},
		{name: "sample.tar.bz2", format: compressedTar(archives.Bz2{})},
		{name: "sample.tar.xz", format: compressedTar(archives.Xz{})},
		{name: "sample.tar.zst", format: compressedTar(archives.Zstd{})},
	}

	for _, test := range formats {
		t.Run(test.name, func(t *testing.T) {
			filesystem := testFilesystem(t)
			writeArchive(t, filesystem, "/"+test.name, test.format, map[string]string{
				"folder/keep.txt": "keep",
				"skip.txt":        "skip",
			})
			if err := filesystem.MkdirAll("/output", 0o750); err != nil {
				t.Fatal(err)
			}

			listing, err := List(context.Background(), filesystem, "/"+test.name, Limits{})
			if err != nil {
				t.Fatal(err)
			}
			if listing.Truncated || len(listing.Entries) != 2 || listing.ListedBytes != 8 {
				t.Fatalf("listing = %#v", listing)
			}
			var progress ExtractProgress
			report, err := Extract(context.Background(), filesystem, ExtractOptions{
				ArchivePath: "/" + test.name, Destination: "/output",
				Selected: []string{"folder"}, Limits: Limits{},
			}, func(next ExtractProgress) error {
				progress = next
				return nil
			})
			if err != nil {
				t.Fatal(err)
			}
			content, err := afero.ReadFile(filesystem, "/output/folder/keep.txt")
			if err != nil || string(content) != "keep" {
				t.Fatalf("extracted content = %q err=%v", content, err)
			}
			if exists, _ := afero.Exists(filesystem, "/output/skip.txt"); exists {
				t.Fatal("unselected entry was extracted")
			}
			if report.ExtractedFiles != 1 || report.ExtractedBytes != 4 || progress.ProcessedItems != progress.TotalItems {
				t.Fatalf("report=%#v progress=%#v", report, progress)
			}
		})
	}
}

func TestUnsafeLinksAndZipSlipEntriesAreBlocked(t *testing.T) {
	root := t.TempDir()
	filesystem := afero.NewBasePathFs(afero.NewOsFs(), root)
	file, err := filesystem.Create("/unsafe.zip")
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	for _, name := range []string{"safe.txt", "../escape.txt", "/absolute.txt", `C:\\outside.txt`} {
		entry, createErr := writer.Create(name)
		if createErr != nil {
			t.Fatal(createErr)
		}
		if _, writeErr := entry.Write([]byte(name)); writeErr != nil {
			t.Fatal(writeErr)
		}
	}
	linkHeader := &zip.FileHeader{Name: "link"}
	linkHeader.SetMode(os.ModeSymlink | 0o777)
	link, err := writer.CreateHeader(linkHeader)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := link.Write([]byte("../../target")); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}

	listing, err := List(context.Background(), filesystem, "/unsafe.zip", Limits{})
	if err != nil {
		t.Fatal(err)
	}
	if len(listing.Entries) != 1 || listing.Entries[0].Path != "safe.txt" || listing.BlockedCount != 4 {
		t.Fatalf("unsafe listing = %#v", listing)
	}
	if err := filesystem.MkdirAll("/output", 0o750); err != nil {
		t.Fatal(err)
	}
	if _, err := Extract(context.Background(), filesystem, ExtractOptions{
		ArchivePath: "/unsafe.zip", Destination: "/output", Selected: []string{"."},
	}, nil); err != nil {
		t.Fatal(err)
	}
	if exists, _ := afero.Exists(filesystem, "/escape.txt"); exists {
		t.Fatal("zip slip entry escaped destination")
	}
	if exists, _ := afero.Exists(filesystem, "/output/link"); exists {
		t.Fatal("archive symlink was extracted")
	}
}

func TestExtractionNeverOverwritesAndRejectsSymlinkDestination(t *testing.T) {
	root := t.TempDir()
	filesystem := afero.NewBasePathFs(afero.NewOsFs(), root)
	writeArchive(t, filesystem, "/sample.zip", archives.Zip{}, map[string]string{"same.txt": "new"})
	if err := filesystem.MkdirAll("/output", 0o750); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(filesystem, "/output/same.txt", []byte("old"), 0o640); err != nil {
		t.Fatal(err)
	}
	report, err := Extract(context.Background(), filesystem, ExtractOptions{
		ArchivePath: "/sample.zip", Destination: "/output", Selected: []string{"same.txt"},
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	content, err := afero.ReadFile(filesystem, "/output/same.txt")
	if err != nil || string(content) != "old" || report.SkippedCount != 1 {
		t.Fatalf("existing content=%q report=%#v err=%v", content, report, err)
	}

	if err := os.Symlink(filepath.Join(root, "output"), filepath.Join(root, "linked-output")); err != nil {
		t.Fatal(err)
	}
	_, err = Extract(context.Background(), filesystem, ExtractOptions{
		ArchivePath: "/sample.zip", Destination: "/linked-output", Selected: []string{"same.txt"},
	}, nil)
	if err == nil {
		t.Fatal("symlink destination was accepted")
	}
}

func TestConflictingArchiveHierarchyAndDuplicatePathsAreBlocked(t *testing.T) {
	filesystem := testFilesystem(t)
	file, err := filesystem.Create("/conflicts.zip")
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	for _, name := range []string{"parent", "parent/child.txt", "same.txt", "same.txt"} {
		entry, createErr := writer.Create(name)
		if createErr != nil {
			t.Fatal(createErr)
		}
		if _, writeErr := entry.Write([]byte(name)); writeErr != nil {
			t.Fatal(writeErr)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}

	listing, err := List(context.Background(), filesystem, "/conflicts.zip", Limits{})
	if err != nil {
		t.Fatal(err)
	}
	if len(listing.Entries) != 2 || listing.BlockedCount != 2 {
		t.Fatalf("conflicting listing = %#v", listing)
	}
}

func TestSafetyLimitsAndDisabledFormats(t *testing.T) {
	filesystem := testFilesystem(t)
	writeArchive(t, filesystem, "/large.zip", archives.Zip{}, map[string]string{"large.bin": string(make([]byte, 128))})
	listing, err := List(context.Background(), filesystem, "/large.zip", Limits{MaxFileBytes: 64, MaxExtractBytes: 256})
	if err != nil {
		t.Fatal(err)
	}
	if !listing.Truncated || listing.LimitReason == "" {
		t.Fatalf("limit listing = %#v", listing)
	}
	if err := filesystem.MkdirAll("/output", 0o750); err != nil {
		t.Fatal(err)
	}
	_, err = Extract(context.Background(), filesystem, ExtractOptions{
		ArchivePath: "/large.zip", Destination: "/output", Selected: []string{"."},
		Limits: Limits{MaxFileBytes: 64, MaxExtractBytes: 256},
	}, nil)
	if !errors.Is(err, ErrLimitExceeded) {
		t.Fatalf("limit error = %v", err)
	}

	for _, fixture := range []struct {
		name string
		data []byte
	}{
		{name: "/broken.7z", data: []byte("7z\xbc\xaf\x27\x1c")},
		{name: "/broken.rar", data: []byte("Rar!\x1a\x07\x00")},
	} {
		if err := afero.WriteFile(filesystem, fixture.name, fixture.data, 0o640); err != nil {
			t.Fatal(err)
		}
		if _, err := List(context.Background(), filesystem, fixture.name, Limits{}); !errors.Is(err, ErrInvalidArchive) {
			t.Fatalf("%s error = %v", fixture.name, err)
		}
	}
}

func TestArchiveIdentityAndCheckerAreEnforced(t *testing.T) {
	filesystem := testFilesystem(t)
	writeArchive(t, filesystem, "/sample.tar", archives.Tar{}, map[string]string{"file.txt": "data"})
	if err := filesystem.MkdirAll("/output", 0o750); err != nil {
		t.Fatal(err)
	}
	listing, err := List(context.Background(), filesystem, "/sample.tar", Limits{})
	if err != nil {
		t.Fatal(err)
	}
	_, err = Extract(context.Background(), filesystem, ExtractOptions{
		ArchivePath: "/sample.tar", Destination: "/output", Selected: []string{"file.txt"},
		SourceSize: listing.SourceSize + 1, SourceModified: listing.SourceModified,
	}, nil)
	if !errors.Is(err, ErrArchiveChanged) {
		t.Fatalf("identity error = %v", err)
	}
	_, err = Extract(context.Background(), filesystem, ExtractOptions{
		ArchivePath: "/sample.tar", Destination: "/output", Selected: []string{"file.txt"},
		Checker: denyChecker{},
	}, nil)
	if err == nil {
		t.Fatal("denied destination was accepted")
	}
}

func compressedTar(compression archives.Compression) archives.CompressedArchive {
	return archives.CompressedArchive{
		Archival: archives.Tar{}, Extraction: archives.Tar{}, Compression: compression,
	}
}

func testFilesystem(t *testing.T) afero.Fs {
	t.Helper()
	return afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
}

func writeArchive(t *testing.T, filesystem afero.Fs, name string, format archives.Archiver, contents map[string]string) {
	t.Helper()
	files := make([]archives.FileInfo, 0, len(contents))
	for name, content := range contents {
		memory := fstest.MapFS{"payload": &fstest.MapFile{
			Data: []byte(content), Mode: 0o640, ModTime: time.Unix(1_700_000_000, 0),
		}}
		info, err := fs.Stat(memory, "payload")
		if err != nil {
			t.Fatal(err)
		}
		memoryCopy := memory
		files = append(files, archives.FileInfo{
			FileInfo: info, NameInArchive: name,
			Open: func() (fs.File, error) { return memoryCopy.Open("payload") },
		})
	}
	output, err := filesystem.Create(name)
	if err != nil {
		t.Fatal(err)
	}
	if err := format.Archive(context.Background(), output, files); err != nil {
		_ = output.Close()
		t.Fatal(err)
	}
	if err := output.Close(); err != nil {
		t.Fatal(err)
	}
}

type denyChecker struct{}

func (denyChecker) Check(string) bool { return false }
