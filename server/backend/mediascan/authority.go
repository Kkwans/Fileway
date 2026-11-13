package mediascan

import (
	"bytes"
	"context"
	"io/fs"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
)

// CheckBinding resolves an exact owner-controlled binding from the catalog,
// checks its revisions and opens its root using fresh source authority. This
// is a precondition for enqueueing, claiming and resuming a scan; it is not a
// replacement for the live checks performed by each subsequent scan open.
func CheckBinding(ctx context.Context, store *medialibrary.Store, actor uint, ref medialibrary.SourceBindingRef, resolve ResolveOwnerAuthority) (*medialibrary.LibrarySource, error) {
	if store == nil || resolve == nil || actor == 0 {
		return nil, fs.ErrInvalid
	}
	library, err := store.GetLibrary(ctx, actor, ref.LibraryID)
	if err != nil {
		return nil, err
	}
	if library.OwnerUserID != actor || library.Role != medialibrary.RoleOwner {
		return nil, medialibrary.ErrForbidden
	}
	if library.ArchivedAt != nil {
		return nil, medialibrary.ErrArchived
	}
	binding, err := store.GetLibrarySource(ctx, actor, ref.LibraryID, ref.SourceID, ref.RootLocator)
	if err != nil {
		return nil, err
	}
	if binding.ID != ref.BindingID || binding.ProofEpoch != ref.ProofEpoch || binding.Source.OwnerUserID != actor || binding.Revision != ref.BindingRevision || binding.Source.AuthorizationRevision != ref.AuthorizationRevision || !bytes.Equal(binding.RootLocator, ref.RootLocator) {
		return nil, medialibrary.ErrRevisionConflict
	}
	if binding.Proof == nil || binding.ProofEpoch < 1 {
		return nil, ErrBindingUnverified
	}
	source, err := mediasource.NewLocal(string(binding.RootLocator), func(ctx context.Context) (mediasource.LocalAuthority, error) {
		return resolve(ctx, binding)
	})
	if err != nil {
		return nil, err
	}
	root, err := source.Open(ctx, "", true)
	if err != nil {
		return nil, err
	}
	if err := root.Close(); err != nil {
		return nil, err
	}
	return binding, nil
}
