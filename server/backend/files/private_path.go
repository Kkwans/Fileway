package files

import "github.com/spf13/afero"

// IsPrivateResourcePath keeps upload parts and private media state inaccessible,
// including current native aliases. Ordinary non-Windows filenames retain their
// lexical/raw-byte contract.
func IsPrivateResourcePath(filesystem afero.Fs, name string) bool {
	if IsUploadPartPath(name) || isPrivateMediaResourcePath(filesystem, name) {
		return true
	}
	return resolvedPrivateResourcePath(filesystem, name)
}
