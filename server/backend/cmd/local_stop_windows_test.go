//go:build windows

package cmd

import (
	"fmt"
	"os"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func TestLocalStopEventSignalsAndReleases(t *testing.T) {
	name := fmt.Sprintf(`Local\Fileway.Server.%032x`, os.Getpid())
	signals := make(chan os.Signal, 1)
	closeEvent, err := watchLocalStopEvent(name, signals)
	if err != nil {
		t.Fatal(err)
	}
	defer closeEvent()
	value, err := windows.UTF16PtrFromString(name)
	if err != nil {
		t.Fatal(err)
	}
	handle, err := windows.OpenEvent(windows.EVENT_MODIFY_STATE, false, value)
	if err != nil {
		t.Fatal(err)
	}
	defer windows.CloseHandle(handle)
	if err := windows.SetEvent(handle); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-signals:
		if got != os.Interrupt {
			t.Fatalf("unexpected signal: %v", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("stop event was not delivered")
	}
}
