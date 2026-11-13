package files

import (
	"github.com/spf13/afero"
	"io/fs"
)

// OpenPublicResource supplements caller authorization with runtime-data privacy.
// Inspect the opened object, not just its path: a concurrent alias replacement
// between Check and Open must not disclose the catalog or its WAL/SHM bytes.
// This does not replace scope/rules checks or the native media source opener.
func OpenPublicResource(filesystem afero.Fs, name string) (afero.File, error) {
	if IsPrivateResourcePath(filesystem, name) {
		return nil, fs.ErrPermission
	}
	file, err := filesystem.Open(name)
	if err != nil {
		return nil, err
	}
	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return nil, err
	}
	if IsPrivateMediaFile(info) {
		_ = file.Close()
		return nil, fs.ErrPermission
	}
	return file, nil
}
