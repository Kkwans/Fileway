//go:build !linux && !windows

package medialibrary

import (
	"errors"
	"os"
)

var errUnsupportedCatalogHost = errors.New("media catalog coordination is supported on Linux and Windows")

func inspectCatalogPath(string) (string, string, uint64, error) {
	return "", "", 0, errUnsupportedCatalogHost
}
func inspectCatalogHandle(*os.File) (string, uint64, error) { return "", 0, errUnsupportedCatalogHost }
func rejectFileMountAlias(string, string) error             { return errUnsupportedCatalogHost }
func tryLockCoordinatorFile(*os.File) error                 { return errUnsupportedCatalogHost }
func unlockCoordinatorFile(*os.File) error                  { return errUnsupportedCatalogHost }
func openCoordinatorFile(string) (*os.File, error)          { return nil, errUnsupportedCatalogHost }
