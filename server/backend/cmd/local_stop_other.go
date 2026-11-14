//go:build !windows

package cmd

import (
	"errors"
	"os"
)

func watchLocalStopEvent(name string, _ chan<- os.Signal) (func(), error) {
	if name != "" {
		return nil, errors.New("local server stop event requires Windows")
	}
	return func() {}, nil
}
