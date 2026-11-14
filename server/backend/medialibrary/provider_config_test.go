package medialibrary

import (
	"bytes"
	"errors"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func fixtureSealed(byteValue byte) SealedProviderCredential {
	return SealedProviderCredential{Ciphertext: []byte{1, byteValue, 3}, Fingerprint: bytes.Repeat([]byte{byteValue}, 32)}
}

func fixtureProvider(t *testing.T, store *Store, actor uint, admin bool, kind, operation string) *ProviderConnection {
	t.Helper()
	id, err := ProviderConnectionID(actor, operation)
	if err != nil {
		t.Fatal(err)
	}
	value, err := store.PutProviderConnection(t.Context(), actor, admin, id, kind, operation, 0, fixtureSealed(7))
	if err != nil {
		t.Fatal(err)
	}
	return value
}

func TestProviderConfigurationReceiptsDoNotExposeCredential(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	personal := fixtureProvider(t, store, 7, false, "user", "personal-create")
	if personal.OwnerUserID != 7 || personal.OwnerKind != "user" || personal.Revision != 1 {
		t.Fatalf("personal: %+v", personal)
	}
	again, err := store.PutProviderConnection(ctx, 7, false, personal.ID, "user", "personal-create", 0,
		SealedProviderCredential{Ciphertext: []byte("different-random-envelope"), Fingerprint: bytes.Repeat([]byte{7}, 32)})
	if err != nil || again.ID != personal.ID || again.Revision != 1 {
		t.Fatalf("replay: %+v %v", again, err)
	}
	var ciphertext []byte
	if err = store.db.QueryRow(`SELECT ciphertext FROM provider_credential WHERE credential_ref=?`, personal.ID).Scan(&ciphertext); err != nil || !bytes.Equal(ciphertext, fixtureSealed(7).Ciphertext) {
		t.Fatal("retry replaced original envelope", err)
	}
	if _, err = store.PutProviderConnection(ctx, 7, false, personal.ID, "user", "personal-create", 0, fixtureSealed(8)); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal("changed credential replay", err)
	}
	if _, err = store.PutProviderConnection(ctx, 8, false, personal.ID, "user", "other-rotate", 1, fixtureSealed(8)); !errors.Is(err, ErrNotFound) {
		t.Fatal("other user rotated personal credential", err)
	}
	rotated, err := store.PutProviderConnection(ctx, 7, false, personal.ID, "user", "personal-rotate", 1, fixtureSealed(8))
	if err != nil || rotated.Revision != 2 {
		t.Fatal("rotation", rotated, err)
	}
	if _, err = store.PutProviderConnection(ctx, 7, false, personal.ID, "user", "stale-rotate", 1, fixtureSealed(9)); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal("stale rotation", err)
	}
	cleared, err := store.ClearProviderConnection(ctx, 7, false, personal.ID, "personal-clear", 2)
	if err != nil || cleared.Status != "missing" || cleared.Revision != 3 {
		t.Fatalf("clear: %+v %v", cleared, err)
	}
	clearedReplay, err := store.ClearProviderConnection(ctx, 7, false, personal.ID, "personal-clear", 2)
	if err != nil || clearedReplay.Revision != 3 {
		t.Fatalf("clear replay: %+v %v", clearedReplay, err)
	}
	var cipherBytes, fingerprintBytes int
	if err = store.db.QueryRow(`SELECT length(ciphertext),length(fingerprint) FROM provider_credential WHERE credential_ref=?`, personal.ID).Scan(&cipherBytes, &fingerprintBytes); err != nil || cipherBytes != 0 || fingerprintBytes != 0 {
		t.Fatal("current envelope was not cleared", err)
	}
	var receipt string
	if err = store.db.QueryRow(`SELECT result_json FROM operation_receipt WHERE actor_user_id=? AND kind=? AND operation_id=?`, 7, "provider.connection.put", "personal-create").Scan(&receipt); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(receipt, "ciphertext") || strings.Contains(receipt, "fingerprint") || strings.Contains(receipt, "envelope") {
		t.Fatal("secret material leaked to receipt")
	}
	instanceID, _ := ProviderConnectionID(7, "instance-create")
	if _, err = store.PutProviderConnection(ctx, 7, false, instanceID, "instance", "instance-create", 0, fixtureSealed(7)); !errors.Is(err, ErrForbidden) {
		t.Fatal("non-admin instance connection", err)
	}
}

func TestProviderPolicyGrantAndEditorAuthorization(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "films")
	grant, err := store.GrantMember(ctx, 7, library.ID, library.Revision, 8, RoleEditor)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.GrantMember(ctx, 7, library.ID, grant.LibraryRevision, 9, RoleViewer); err != nil {
		t.Fatal(err)
	}
	instance := fixtureProvider(t, store, 1, true, "instance", "instance")
	ownerPersonal := fixtureProvider(t, store, 7, false, "user", "owner-token")
	editorPersonal := fixtureProvider(t, store, 8, false, "user", "editor-token")
	initial, err := store.GetLibraryMetadataPolicy(ctx, 7, library.ID)
	if err != nil || initial.Mode != "local_only" || initial.Revision != 0 || initial.AllowEditorJobs {
		t.Fatalf("default policy: %+v %v", initial, err)
	}
	if _, err = store.GetLibraryMetadataPolicy(ctx, 9, library.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("viewer policy access", err)
	}
	if _, err = store.CheckProviderUse(ctx, 7, library.ID, ownerPersonal.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("local-only use", err)
	}
	policy := LibraryMetadataPolicy{Mode: "local_first_online", ConnectionID: instance.ID, Language: "zh-CN", Region: "CN"}
	if _, err = store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "policy-before-grant", 0, policy); !errors.Is(err, ErrNotFound) {
		t.Fatal("ungranted instance use", err)
	}
	grantProvider, err := store.SetProviderGrant(ctx, 1, true, instance.ID, "library", library.ID, "instance-grant", 1, true)
	if err != nil || grantProvider.ConnectionRevision != 2 {
		t.Fatal("instance grant", grantProvider, err)
	}
	policyResult, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "policy-enable", 0, policy)
	if err != nil || policyResult.Revision != 1 {
		t.Fatal("policy enable", policyResult, err)
	}
	authority, envelope, err := store.GetProviderCredentialForUse(ctx, 7, library.ID, instance.ID)
	if err != nil || authority.ConnectionRevision != 2 || authority.PolicyRevision != 1 || !bytes.Equal(envelope, fixtureSealed(7).Ciphertext) {
		t.Fatalf("owner granted instance: %+v %v", authority, err)
	}
	if _, err = store.CheckProviderUse(ctx, 8, library.ID, instance.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("editor without delegation", err)
	}
	if _, err = store.SetLibraryMetadataPolicy(ctx, 8, library.ID, "editor-policy", 1, policy); !errors.Is(err, ErrForbidden) {
		t.Fatal("editor policy write", err)
	}
	policy.AllowEditorJobs = true
	policyResult, err = store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "policy-editor", 1, policy)
	if err != nil || policyResult.Revision != 2 {
		t.Fatal("delegate editor", policyResult, err)
	}
	if _, err = store.CheckProviderUse(ctx, 8, library.ID, instance.ID); err != nil {
		t.Fatal("editor granted instance", err)
	}
	if _, err = store.CheckProviderUse(ctx, 8, library.ID, editorPersonal.ID); err != nil {
		t.Fatal("editor own explicitly selected credential", err)
	}
	if _, err = store.ClearProviderConnection(ctx, 8, false, editorPersonal.ID, "editor-clear", 1); err != nil {
		t.Fatal("clear editor credential", err)
	}
	if _, _, err = store.GetProviderCredentialForUse(ctx, 8, library.ID, editorPersonal.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("cleared credential still usable", err)
	}
	if _, err = store.CheckProviderUse(ctx, 8, library.ID, ownerPersonal.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("editor used owner's token", err)
	}
	if _, err = store.CheckProviderUse(ctx, 9, library.ID, instance.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("viewer job use", err)
	}
	connections, err := store.ListProviderConnections(ctx, 8, false, false, "", 50)
	if err != nil || len(connections.Items) != 2 {
		t.Fatalf("editor connections: %+v %v", connections, err)
	}
	for _, connection := range connections.Items {
		if connection.ID == ownerPersonal.ID {
			t.Fatal("other user's connection leaked")
		}
	}
	if err = store.VerifyProviderConnections(ctx, 8, false, false, connections.Items); err != nil {
		t.Fatal("visible page rejected", err)
	}
	firstPage, err := store.ListProviderConnections(ctx, 8, false, false, "", 1)
	if err != nil || len(firstPage.Items) != 1 || firstPage.NextCursor == "" {
		t.Fatalf("first connection page: %+v %v", firstPage, err)
	}
	secondPage, err := store.ListProviderConnections(ctx, 8, false, false, firstPage.NextCursor, 1)
	if err != nil || len(secondPage.Items) != 1 || secondPage.Items[0].ID == firstPage.Items[0].ID || secondPage.NextCursor != "" {
		t.Fatalf("second connection page: %+v %v", secondPage, err)
	}
	ownedOnly, err := store.ListProviderConnections(ctx, 8, false, true, "", 50)
	if err != nil || len(ownedOnly.Items) != 1 || ownedOnly.Items[0].ID != editorPersonal.ID {
		t.Fatalf("sharing disabled leaked library grant: %+v %v", ownedOnly, err)
	}
	rotated, err := store.PutProviderConnection(ctx, 1, true, instance.ID, "instance", "instance-rotate", 2, fixtureSealed(8))
	if err != nil || rotated.Revision != 3 {
		t.Fatal("instance rotation", rotated, err)
	}
	if err = store.VerifyProviderConnections(ctx, 8, false, false, connections.Items); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal("stale connection page accepted", err)
	}
	newAuthority, newEnvelope, err := store.GetProviderCredentialForUse(ctx, 7, library.ID, instance.ID)
	if err != nil || newAuthority.ConnectionRevision != 3 || newAuthority.PolicyRevision != 2 || !bytes.Equal(newEnvelope, fixtureSealed(8).Ciphertext) {
		t.Fatalf("rotation fence: %+v %v", newAuthority, err)
	}
	revoked, err := store.SetProviderGrant(ctx, 1, true, instance.ID, "library", library.ID, "instance-revoke", 3, false)
	if err != nil || revoked.ConnectionRevision != 4 {
		t.Fatal("instance revoke", revoked, err)
	}
	if _, err = store.CheckProviderUse(ctx, 7, library.ID, instance.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("revoked use", err)
	}
	if _, envelope, err = store.GetProviderCredentialForUse(ctx, 7, library.ID, instance.ID); !errors.Is(err, ErrNotFound) || len(envelope) != 0 {
		t.Fatal("revoked envelope exposed", err)
	}
	if _, err = store.SetProviderGrant(ctx, 1, true, instance.ID, "user", "9", "viewer-user-grant", 4, true); err != nil {
		t.Fatal("user grant", err)
	}
	viewerConnections, err := store.ListProviderConnections(ctx, 9, false, false, "", 50)
	if err != nil || len(viewerConnections.Items) != 1 || viewerConnections.Items[0].ID != instance.ID {
		t.Fatalf("direct grant missing: %+v %v", viewerConnections, err)
	}
	if _, err = store.CheckProviderUse(ctx, 9, library.ID, instance.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("grant bypassed viewer role", err)
	}
	grantPage, err := store.ListProviderGrants(ctx, 1, true, instance.ID, "", "", 1)
	if err != nil || len(grantPage.Items) != 1 || grantPage.NextKind == "" || grantPage.NextID == "" {
		t.Fatalf("first grant page: %+v %v", grantPage, err)
	}
	grantNext, err := store.ListProviderGrants(ctx, 1, true, instance.ID, grantPage.NextKind, grantPage.NextID, 1)
	if err != nil || len(grantNext.Items) != 1 || grantNext.NextKind != "" {
		t.Fatalf("second grant page: %+v %v", grantNext, err)
	}
	if _, err = store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "policy-enable", 0, LibraryMetadataPolicy{Mode: "local_first_online", ConnectionID: instance.ID, Language: "zh-CN", Region: "CN"}); !errors.Is(err, ErrNotFound) {
		t.Fatal("revoked policy receipt replay", err)
	}
}

func TestMetadataPolicyV11MigratesAndSwitchesPriority(t *testing.T) {
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "provider-policy.db")
	store, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "Metadata policy")
	conn := fixtureProvider(t, store, 7, false, "user", "migration-connection")
	// Recreate the released v11 leaf table without disturbing the connection.
	_, err = store.db.ExecContext(ctx, `DROP TABLE item_provider_match; DROP TABLE library_metadata_policy;`+schemaV11[strings.Index(schemaV11, "CREATE TABLE library_metadata_policy"):]+`PRAGMA user_version=11`)
	if err != nil {
		t.Fatal(err)
	}
	policy := LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: conn.ID, Language: "zh-CN", Region: "CN", AllowEditorJobs: true}
	before, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "v11-local-first", 0, policy)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	after, err := store.GetLibraryMetadataPolicy(ctx, 7, library.ID)
	if err != nil || *after != *before {
		t.Fatalf("v11 policy changed after migration: %+v %v", after, err)
	}
	policy.Mode = MetadataOnlineFirst
	online, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "v12-online-first", before.Revision, policy)
	if err != nil || online.Mode != MetadataOnlineFirst || online.Revision != before.Revision+1 {
		t.Fatalf("online-first update: %+v %v", online, err)
	}
	authority, err := store.CheckProviderUse(ctx, 7, library.ID, conn.ID)
	if err != nil || authority.PolicyRevision != online.Revision {
		t.Fatalf("online-first authority: %+v %v", authority, err)
	}
	if _, err = store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "v12-invalid", online.Revision, LibraryMetadataPolicy{Mode: MetadataOnlineFirst}); !errors.Is(err, ErrInvalid) {
		t.Fatal("online-first without a connection", err)
	}
}

func TestProviderConcurrentRevisionAndSchemaMigration(t *testing.T) {
	path := filepath.Join(t.TempDir(), "catalog.db")
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	library := createLibrary(t, store, 7, "preserved")
	if _, err = store.db.Exec(`DROP TABLE library_metadata_policy; DROP TABLE provider_grant; DROP TABLE provider_connection; DROP TABLE provider_credential; PRAGMA user_version=10`); err != nil {
		t.Fatal(err)
	}
	if err = store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	if _, err = store.GetLibrary(t.Context(), 7, library.ID); err != nil {
		t.Fatal("catalog not preserved", err)
	}
	connection := fixtureProvider(t, store, 7, false, "user", "race-create")
	results := make(chan error, 2)
	var workers sync.WaitGroup
	for _, name := range []string{"one", "two"} {
		workers.Add(1)
		go func(id string) {
			defer workers.Done()
			_, err := store.PutProviderConnection(t.Context(), 7, false, connection.ID, "user", id, 1, fixtureSealed(9))
			results <- err
		}(name)
	}
	workers.Wait()
	close(results)
	var success, conflicts int
	for err := range results {
		if err == nil {
			success++
		} else if errors.Is(err, ErrRevisionConflict) {
			conflicts++
		} else {
			t.Fatal(err)
		}
	}
	if success != 1 || conflicts != 1 {
		t.Fatalf("success=%d conflicts=%d", success, conflicts)
	}
}
