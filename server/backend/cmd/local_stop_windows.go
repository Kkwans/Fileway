//go:build windows

package cmd

import (
	"errors"
	"os"
	"regexp"

	"golang.org/x/sys/windows"
)

var localStopName = regexp.MustCompile(`^Local\\Fileway\.Server\.[a-f0-9]{32}$`)

// The event is scoped to one local data root and is used only by the Windows
// client that owns that deployment. Console signals remain available.
func watchLocalStopEvent(name string, signals chan<- os.Signal) (func(), error) {
	if name == "" {
		return func() {}, nil
	}
	if !localStopName.MatchString(name) {
		return nil, errors.New("invalid local server stop event")
	}
	value, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return nil, err
	}
	handle, err := windows.CreateEvent(nil, 0, 0, value)
	if err != nil {
		if handle != 0 {
			_ = windows.CloseHandle(handle)
		}
		return nil, err
	}
	canceled := make(chan struct{})
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		result, waitErr := windows.WaitForSingleObject(handle, windows.INFINITE)
		if waitErr == nil && result == windows.WAIT_OBJECT_0 {
			select {
			case <-canceled:
				return
			default:
			}
			select {
			case signals <- os.Interrupt:
			default:
			}
		}
	}()
	return func() {
		close(canceled)
		_ = windows.SetEvent(handle)
		<-finished
		_ = windows.CloseHandle(handle)
	}, nil
}
