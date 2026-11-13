package medialibrary

import (
	"errors"
	"fmt"
	"os"
	"sync"
)

var (
	ErrCatalogIdentityChanged = errors.New("media catalog identity or link count changed")
	ErrCatalogHardLinked      = errors.New("media catalog must have exactly one hard link")
	ErrCoordinatorActive      = errors.New("media scan coordinator must be released before closing the catalog")
	ErrCoordinatorUnavailable = errors.New("media scan coordinator is unavailable")
)

// The handle is shared across Store instances within this process. On POSIX,
// closing any descriptor for a file can release process-scoped SQLite locks;
// retain this one until the last SQLite connection using the inode is closed.
type catalogIdentity struct {
	file      *os.File
	canonical string
	key       string
	refs      int
}

var catalogIdentities = struct {
	sync.Mutex
	byKey map[string]*catalogIdentity
}{byKey: make(map[string]*catalogIdentity)}

// caller holds catalogIdentities.Mutex, including across file creation.
func registerCatalogIdentity(path string, created *os.File) (*catalogIdentity, error) {
	canonical, key, links, err := inspectCatalogPath(path)
	if err != nil {
		return nil, err
	}
	if links != 1 {
		return nil, ErrCatalogHardLinked
	}
	if err := rejectFileMountAlias(canonical, key); err != nil {
		return nil, err
	}
	if existing := catalogIdentities.byKey[key]; existing != nil {
		if created != nil {
			return nil, errors.New("new catalog identity unexpectedly already registered")
		}
		if err := validateCatalogIdentity(existing); err != nil {
			return nil, err
		}
		existing.refs++
		return existing, nil
	}
	f := created
	if f == nil {
		f, err = os.Open(canonical)
		if err != nil {
			return nil, err
		}
	}
	actualKey, actualLinks, err := inspectCatalogHandle(f)
	if err != nil || actualKey != key || actualLinks != 1 {
		if created == nil {
			_ = f.Close()
		}
		if err != nil {
			return nil, err
		}
		return nil, ErrCatalogIdentityChanged
	}
	identity := &catalogIdentity{file: f, canonical: canonical, key: key, refs: 1}
	catalogIdentities.byKey[key] = identity
	return identity, nil
}

func releaseCatalogIdentity(identity *catalogIdentity) error {
	if identity == nil {
		return nil
	}
	catalogIdentities.Lock()
	defer catalogIdentities.Unlock()
	identity.refs--
	if identity.refs > 0 {
		return nil
	}
	delete(catalogIdentities.byKey, identity.key)
	return identity.file.Close()
}

func validateCatalogIdentity(identity *catalogIdentity) error {
	if identity == nil {
		return ErrCoordinatorUnavailable
	}
	canonical, currentKey, links, err := inspectCatalogPath(identity.canonical)
	if err != nil {
		return fmt.Errorf("%w: catalog path: %v", ErrCatalogIdentityChanged, err)
	}
	key, handleLinks, err := inspectCatalogHandle(identity.file)
	if err != nil || canonical != identity.canonical || currentKey != identity.key || key != identity.key || links != 1 || handleLinks != 1 {
		return ErrCatalogIdentityChanged
	}
	return nil
}
