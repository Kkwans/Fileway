package mediascan

import (
	"os"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
)

// A path-only identity is deliberately tagged, so downstream code cannot treat
// an equal locator as proof that the underlying object or timeline is unchanged.
func objectKey(file *os.File, locator []byte) ([]byte, IdentityKind) {
	if key, ok := nativeObjectKey(file); ok {
		return key, IdentityNative
	}
	return medialibrary.UnverifiedPathObjectKey(locator), IdentityPathUnverified
}
