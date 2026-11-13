//go:build !linux && !windows

package mediascan

import "os"

func nativeObjectKey(*os.File) ([]byte, bool) { return nil, false }

func nativeDirectoryKey(*os.File) ([]byte, bool) { return nil, false }
