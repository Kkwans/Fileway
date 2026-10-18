package fbhttp

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/spf13/afero"
)

func TestSetContentDisposition(t *testing.T) {
	t.Parallel()

	testCases := map[string]struct {
		filename string
		inline   bool
		expected string
	}{
		"inline simple filename": {
			filename: "document.pdf",
			inline:   true,
			expected: "inline; filename*=utf-8''" + url.PathEscape("document.pdf"),
		},
		"attachment simple filename": {
			filename: "document.pdf",
			inline:   false,
			expected: "attachment; filename*=utf-8''" + url.PathEscape("document.pdf"),
		},
		"inline non-ASCII filename": {
			filename: "日本語.txt",
			inline:   true,
			expected: "inline; filename*=utf-8''" + url.PathEscape("日本語.txt"),
		},
		"attachment non-ASCII filename": {
			filename: "日本語.txt",
			inline:   false,
			expected: "attachment; filename*=utf-8''" + url.PathEscape("日本語.txt"),
		},
		"inline filename with spaces": {
			filename: "my file.txt",
			inline:   true,
			expected: "inline; filename*=utf-8''" + url.PathEscape("my file.txt"),
		},
		"attachment filename with spaces": {
			filename: "my file.txt",
			inline:   false,
			expected: "attachment; filename*=utf-8''" + url.PathEscape("my file.txt"),
		},
	}

	for name, tc := range testCases {
		t.Run(name, func(t *testing.T) {
			t.Parallel()

			recorder := httptest.NewRecorder()
			req, err := http.NewRequest(http.MethodGet, "/test", http.NoBody)
			if err != nil {
				t.Fatalf("failed to create request: %v", err)
			}
			if tc.inline {
				req.URL.RawQuery = "inline=true"
			}

			file := &files.FileInfo{Name: tc.filename}

			setContentDisposition(recorder, req, file)

			got := recorder.Header().Get("Content-Disposition")
			if got != tc.expected {
				t.Errorf("Content-Disposition = %q, want %q", got, tc.expected)
			}
		})
	}
}

func TestRawFileHandlerBoundsOpenEndedInlineVideoRanges(t *testing.T) {
	t.Parallel()

	const fileSize = inlineVideoRangeChunkSize*2 + 17
	fs := afero.NewMemMapFs()
	if err := afero.WriteFile(fs, "/large.mp4", bytes.Repeat([]byte("v"), int(fileSize)), 0o600); err != nil {
		t.Fatalf("write fixture: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/large.mp4?inline=true", nil)
	req.Header.Set("Range", "bytes=0-")
	recorder := httptest.NewRecorder()
	file := &files.FileInfo{Fs: fs, Path: "/large.mp4", Name: "large.mp4"}

	if _, err := rawFileHandler(recorder, req, file); err != nil {
		t.Fatalf("rawFileHandler returned error: %v", err)
	}
	if recorder.Code != http.StatusPartialContent {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusPartialContent)
	}
	if got, want := recorder.Header().Get("Content-Range"), "bytes 0-2097151/4194321"; got != want {
		t.Fatalf("Content-Range = %q, want %q", got, want)
	}
	body, err := io.ReadAll(recorder.Result().Body)
	if err != nil {
		t.Fatalf("read response body: %v", err)
	}
	if got, want := int64(len(body)), inlineVideoRangeChunkSize; got != want {
		t.Fatalf("body length = %d, want %d", got, want)
	}
}

func TestRawFileHandlerDoesNotBoundAttachmentVideoRanges(t *testing.T) {
	t.Parallel()

	const fileSize = inlineVideoRangeChunkSize + 17
	fs := afero.NewMemMapFs()
	if err := afero.WriteFile(fs, "/video.mp4", bytes.Repeat([]byte("v"), int(fileSize)), 0o600); err != nil {
		t.Fatalf("write fixture: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/video.mp4", nil)
	req.Header.Set("Range", "bytes=0-")
	recorder := httptest.NewRecorder()
	file := &files.FileInfo{Fs: fs, Path: "/video.mp4", Name: "video.mp4"}

	if _, err := rawFileHandler(recorder, req, file); err != nil {
		t.Fatalf("rawFileHandler returned error: %v", err)
	}
	if got, want := recorder.Header().Get("Content-Range"), "bytes 0-2097168/2097169"; got != want {
		t.Fatalf("Content-Range = %q, want %q", got, want)
	}
}

func TestRawFileHandlerConditionalSourceVersion(t *testing.T) {
	t.Parallel()
	fs := afero.NewMemMapFs()
	if err := afero.WriteFile(fs, "/resume.bin", []byte("abc"), 0o600); err != nil {
		t.Fatalf("write fixture: %v", err)
	}
	file := &files.FileInfo{Fs: fs, Path: "/resume.bin", Name: "resume.bin", Size: 3, ModTime: time.Unix(42, 0).UTC()}

	first := httptest.NewRecorder()
	if _, err := rawFileHandler(first, httptest.NewRequest(http.MethodGet, "/resume.bin", nil), file); err != nil {
		t.Fatalf("initial raw read: %v", err)
	}
	version := first.Header().Get("X-Fileway-Source-Version")
	if version == "" {
		t.Fatal("missing source version")
	}

	matching := httptest.NewRequest(http.MethodGet, "/resume.bin", nil)
	matching.Header.Set("X-Fileway-Source-Version", version)
	matchingResponse := httptest.NewRecorder()
	if _, err := rawFileHandler(matchingResponse, matching, file); err != nil || matchingResponse.Code != http.StatusOK {
		t.Fatalf("matching source version status=%d err=%v", matchingResponse.Code, err)
	}

	stale := httptest.NewRequest(http.MethodGet, "/resume.bin", nil)
	stale.Header.Set("X-Fileway-Source-Version", "v1-stale")
	staleResponse := httptest.NewRecorder()
	if status, err := rawFileHandler(staleResponse, stale, file); status != http.StatusPreconditionFailed || err == nil {
		t.Fatalf("stale source version status=%d err=%v", status, err)
	}
}
