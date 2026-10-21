package archivefs

import (
	"bytes"
	"context"
	"testing"

	"github.com/spf13/afero"
)

// These formats have readers but no writers in the pinned archive library.
func TestReadOnlyFormatsListPreviewAndSelectedExtraction(t *testing.T) {
	for _, format := range []string{"7z", "rar"} {
		t.Run(format, func(t *testing.T) {
			filesystem := testFilesystem(t)
			fixture, err := afero.ReadFile(afero.NewOsFs(), "testdata/owned."+format)
			if err != nil {
				t.Fatal(err)
			}
			source := "/owned." + format
			if err := afero.WriteFile(filesystem, source, fixture, 0o600); err != nil {
				t.Fatal(err)
			}
			listing, err := List(context.Background(), filesystem, source, Limits{})
			if err != nil {
				t.Fatal(err)
			}
			if listing.Format != format || listing.Truncated || listing.BlockedCount != 0 || len(listing.Entries) != 2 {
				t.Fatalf("listing=%#v", listing)
			}
			var preview bytes.Buffer
			_, err = MaterializeEntry(context.Background(), entryInput(t, filesystem, source, "folder/keep.txt"), &preview, Limits{}, nil, nil)
			if err != nil || preview.String() != "owned archive text\n" {
				t.Fatalf("preview=%q err=%v", preview.String(), err)
			}
			if err := filesystem.MkdirAll("/output", 0o750); err != nil {
				t.Fatal(err)
			}
			report, err := Extract(context.Background(), filesystem, ExtractOptions{ArchivePath: source, Destination: "/output", Selected: []string{"folder"}}, nil)
			if err != nil {
				t.Fatal(err)
			}
			content, err := afero.ReadFile(filesystem, "/output/folder/keep.txt")
			if err != nil || string(content) != "owned archive text\n" || report.ExtractedFiles != 1 {
				t.Fatalf("report=%#v content=%q err=%v", report, content, err)
			}
			if exists, _ := afero.Exists(filesystem, "/output/skip.txt"); exists {
				t.Fatal("unselected entry extracted")
			}
		})
	}
}
