package files

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/spf13/afero"
)

// MediaLibraryDirectory is reserved even while the optional catalog is disabled.
// A dedicated directory avoids hiding an existing media or account-data parent.
const MediaLibraryDirectory = ".fileway-media"

type privateMediaDirectory struct {
	root *os.Root
	info os.FileInfo
	file string
}

var privateMediaDirectories = struct {
	sync.RWMutex
	entries map[*privateMediaDirectory]struct{}
}{entries: make(map[*privateMediaDirectory]struct{})}

// ProtectMediaLibraryDatabase retains a directory handle so renaming its parent
// cannot detach privacy checks from the catalog. Keep this protection for the
// entire server lifetime, including when opening or migrating the catalog fails.
func ProtectMediaLibraryDatabase(database string) (func() error, error) {
	if !filepath.IsAbs(database) || !strings.EqualFold(filepath.Base(filepath.Dir(database)), MediaLibraryDirectory) {
		return nil, errors.New("media catalog requires its dedicated directory")
	}
	before, err := os.Lstat(filepath.Dir(database))
	if err != nil {
		return nil, err
	}
	if !before.IsDir() || before.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("media catalog directory must not be a link")
	}
	root, err := os.OpenRoot(filepath.Dir(database))
	if err != nil {
		return nil, err
	}
	info, err := root.Stat(".")
	if err != nil || !os.SameFile(before, info) {
		_ = root.Close()
		return nil, errors.New("media catalog directory changed during protection")
	}
	entry := &privateMediaDirectory{root: root, info: info, file: filepath.Base(database)}
	privateMediaDirectories.Lock()
	privateMediaDirectories.entries[entry] = struct{}{}
	privateMediaDirectories.Unlock()
	var once sync.Once
	var closeErr error
	return func() error {
		once.Do(func() {
			privateMediaDirectories.Lock()
			defer privateMediaDirectories.Unlock()
			delete(privateMediaDirectories.entries, entry)
			closeErr = root.Close()
		})
		return closeErr
	}, nil
}

func isMediaLibraryNamespace(name string) bool {
	for _, component := range strings.Split(filepath.ToSlash(name), "/") {
		if strings.EqualFold(component, MediaLibraryDirectory) {
			return true
		}
	}
	return false
}

// IsPrivateMediaFile checks the actual opened object rather than re-resolving
// its pathname. Read paths can use this before consuming data after a path
// authorization check, which by itself cannot prevent a concurrent link swap.
func IsPrivateMediaFile(info os.FileInfo) bool {
	if info == nil {
		return true
	}
	privateMediaDirectories.RLock()
	defer privateMediaDirectories.RUnlock()
	return isPrivateMediaFileLocked(info)
}

func isPrivateMediaFileLocked(info os.FileInfo) bool {
	for entry := range privateMediaDirectories.entries {
		if os.SameFile(info, entry.info) {
			return true
		}
		if !info.IsDir() {
			for _, suffix := range []string{"", "-wal", "-shm", "-journal"} {
				private, err := entry.root.Stat(entry.file + suffix)
				if err == nil && os.SameFile(info, private) {
					return true
				}
			}
		}
	}
	return false
}

func isPrivateMediaResourcePath(filesystem afero.Fs, name string) bool {
	if isMediaLibraryNamespace(name) {
		return true
	}
	var native string
	switch fs := filesystem.(type) {
	case *afero.BasePathFs:
		// Memory filesystems have no host aliases, even if their scope happens
		// to use a host pathname. Lstat support follows the wrapped filesystem.
		_, supported, err := fs.LstatIfPossible(name)
		if !supported {
			return false
		}
		if err != nil && !os.IsNotExist(err) {
			return true
		}
		native = afero.FullBaseFsPath(fs, name)
	case *DriveFs:
		var err error
		native, err = fs.RealPath(name)
		if errors.Is(err, errVirtualRoot) {
			return false
		}
		if err != nil {
			return true
		}
	case *afero.OsFs:
		var err error
		native, err = filepath.Abs(name)
		if err != nil {
			return true
		}
	default:
		return false
	}
	return isPrivateMediaNativePath(native)
}

func isPrivateMediaNativePath(native string) bool {
	if isMediaLibraryNamespace(native) {
		return true
	}
	// A write destination may not exist yet. Resolve its nearest existing
	// ancestor, including aliases to the private directory or a moved parent.
	for candidate := filepath.Clean(native); ; candidate = filepath.Dir(candidate) {
		info, err := os.Stat(candidate)
		if err != nil {
			if !os.IsNotExist(err) {
				return true
			}
		} else {
			resolved, err := filepath.EvalSymlinks(candidate)
			if err != nil || isMediaLibraryNamespace(resolved) {
				return true
			}
			privateMediaDirectories.RLock()
			defer privateMediaDirectories.RUnlock()
			// A direct hard link does not contain the private directory name.
			// Stat relative to the retained root, including WAL/SHM created later.
			if isPrivateMediaFileLocked(info) {
				return true
			}
			for entry := range privateMediaDirectories.entries {
				for ancestor := resolved; ; ancestor = filepath.Dir(ancestor) {
					current, statErr := os.Stat(ancestor)
					if statErr != nil || os.SameFile(current, entry.info) {
						return true
					}
					if filepath.Dir(ancestor) == ancestor {
						break
					}
				}
			}
			return false
		}
		if filepath.Dir(candidate) == candidate {
			return false
		}
	}
}
