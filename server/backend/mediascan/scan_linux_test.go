//go:build linux

package mediascan

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"unicode/utf8"
)

func TestScanLocalPreservesOpaqueLinuxFilenameBytes(t *testing.T) {
	f := newScanFixture(t)
	name := string([]byte{'n', 0xff, '\\', 'x', '.', 'm', 'k', 'v'})
	if err := os.WriteFile(filepath.Join(f.root, "media", name), []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	sink := &observationSink{}
	if _, err := ScanLocal(t.Context(), 7, f.binding, f.resolve, sink, Options{}); err != nil {
		t.Fatal(err)
	}
	want := append([]byte("/media/"), []byte(name)...)
	if len(sink.observations) != 1 || !bytes.Equal(sink.observations[0].Locator, want) || !utf8.ValidString(sink.observations[0].InitialTitle) {
		t.Fatalf("opaque locator was changed: %+v", sink.observations)
	}
}
