//go:build windows

package mediascan

import (
	"fmt"
	"os"
	"unsafe"

	"golang.org/x/sys/windows"
)

func nativeObjectKey(file *os.File) ([]byte, bool) {
	if file == nil {
		return nil, false
	}
	var id struct {
		volume uint64
		file   [16]byte
	}
	var basic struct {
		created, accessed, modified, changed int64
		attrs, padding                       uint32
	}
	handle := windows.Handle(file.Fd())
	if windows.GetFileInformationByHandleEx(handle, windows.FileIdInfo, (*byte)(unsafe.Pointer(&id)), uint32(unsafe.Sizeof(id))) != nil ||
		windows.GetFileInformationByHandleEx(handle, windows.FileBasicInfo, (*byte)(unsafe.Pointer(&basic)), uint32(unsafe.Sizeof(basic))) != nil ||
		id.file == [16]byte{} || basic.created == 0 || basic.attrs&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		return nil, false
	}
	return []byte(fmt.Sprintf("fileway/local/windows/v1:%d:%x:%d", id.volume, id.file, basic.created)), true
}

func nativeDirectoryKey(file *os.File) ([]byte, bool) { return nativeObjectKey(file) }
