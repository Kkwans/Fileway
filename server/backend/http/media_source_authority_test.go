package fbhttp

import (
	"context"
	"errors"
	"io/fs"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestLocalMediaAuthorityReloadsOwnerAndRulesWithoutViewerImpersonation(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "source-owner", Perm: users.Permissions{Download: true}}, users.User{Username: "viewer"})
	owner := trashHTTPUserByName(t, h, "source-owner")
	viewer := trashHTTPUserByName(t, h, "viewer")
	resolve := localMediaAuthority(h.storage, h.server, owner.ID)
	authority, err := resolve(t.Context())
	if err != nil || !authority.CanRead || !authority.Check("/films/a.mkv") {
		t.Fatalf("initial authority: %v", err)
	}
	if authority.FS != h.fs[owner.ID] || authority.FS == h.fs[viewer.ID] {
		t.Fatal("wrong filesystem principal")
	}
	fresh, err := h.storage.Users.Get(h.server.Root, owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	fresh.Rules = []rules.Rule{{Path: "/films", Allow: false}}
	if err := h.storage.Users.Update(fresh, "Rules"); err != nil {
		t.Fatal(err)
	}
	changed, err := resolve(t.Context())
	if err != nil || changed.Check("/films/a.mkv") {
		t.Fatalf("stale owner rules: %v", err)
	}
	fresh.Perm.Download = false
	if err := h.storage.Users.Update(fresh, "Perm"); err != nil {
		t.Fatal(err)
	}
	if _, err := resolve(t.Context()); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("revoked source still readable: %v", err)
	}
	if viewer.Perm.Download {
		t.Fatal("viewer permission elevated")
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := resolve(ctx); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestLocalMediaAuthorityReloadsGlobalRulesAndRejectsMissingOwner(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	if _, err := resolveLocalMediaConnection(t.Context(), h.storage, h.server, owner.ID, "unrecognized-connection"); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("unknown connection adopted owner filesystem: %v", err)
	}
	if _, err := resolveLocalMediaConnection(t.Context(), h.storage, h.server, owner.ID, localMediaWorkspaceConfiguration); err != nil {
		t.Fatalf("registered workspace unavailable: %v", err)
	}
	resolve := localMediaAuthority(h.storage, h.server, owner.ID)
	config, err := h.storage.Settings.Get()
	if err != nil {
		t.Fatal(err)
	}
	config.Rules = []rules.Rule{{Path: "/restricted", Allow: false}}
	if err := h.storage.Settings.Save(config); err != nil {
		t.Fatal(err)
	}
	authority, err := resolve(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if authority.Check("/restricted/a.mkv") || authority.Check("/.fileway-media/catalog.db") {
		t.Fatal("global/private rules bypassed")
	}
	if _, err := localMediaAuthority(h.storage, h.server, 0)(t.Context()); !errors.Is(err, fs.ErrPermission) {
		t.Fatal(err)
	}
	if _, err := localMediaAuthority(h.storage, h.server, owner.ID+1000)(t.Context()); !errors.Is(err, fs.ErrPermission) {
		t.Fatal(err)
	}
}
