package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"testing"
)

func playbackFixture(t *testing.T) (*Store, *Library, *LibrarySource, *ScanLease, *ScanCommit, PlaybackVariant) {
	t.Helper()
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "access-fixture")
	input := testObservation("verified-native", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>Film</title><plot>Summary</plot><thumb aspect="poster">poster.jpg</thumb></movie>`)
	file := scanNFO(t, store, binding, lease, input, 1)
	page, err := store.ListPlaybackVariants(t.Context(), 7, library.ID, file.ItemID, "", 20)
	if err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	return store, library, binding, lease, file, page.Items[0]
}

func TestPlaybackEvidenceKeepsViewerSeparateAndMetadataDoesNotFence(t *testing.T) {
	store, library, binding, _, file, variant := playbackFixture(t)
	ctx := t.Context()
	grant, err := store.GrantMember(ctx, 7, library.ID, binding.LibraryRevision, 8, RoleViewer)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.GetLibrarySourceByID(ctx, 8, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("viewer gained source management", err)
	}
	access, err := store.ResolvePlayback(ctx, 8, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || access.ViewerUserID != 8 || access.Source.OwnerUserID != 7 || access.MembershipRole != RoleViewer || access.MembershipRevision != grant.Revision || access.Binding.ProofEpoch != binding.ProofEpoch || access.MappingRevision != 1 || access.EditionMappingRevision != 1 || access.FileRevision != file.FileRevision || string(access.Locator) != "/videos/one.mkv" {
		t.Fatal(access, err)
	}
	if _, err := store.ResolvePlayback(ctx, 9, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.ResolvePlayback(ctx, 8, library.ID, "wrong", variant.EditionID, variant.VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(access)
	if err != nil || strings.Contains(string(encoded), "/videos/") || strings.Contains(string(encoded), "test-configuration") || strings.Contains(string(encoded), "verified-native") {
		t.Fatal(string(encoded), err)
	}
	item, err := store.GetItemDetail(ctx, 7, library.ID, file.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.ApplyMetadata(ctx, 7, library.ID, file.ItemID, item.Item.Revision, "metadata-only", MetadataPatch{Fields: map[string]MetadataFieldPatch{"plot": {Values: []string{"Edited"}}}}); err != nil {
		t.Fatal(err)
	}
	again, err := store.ResolvePlayback(ctx, 8, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || again.MappingRevision != access.MappingRevision || again.EditionMappingRevision != access.EditionMappingRevision || again.FileRevision != access.FileRevision || again.MembershipRevision != access.MembershipRevision || again.LibraryAccessRevision != access.LibraryAccessRevision {
		t.Fatal(again, err)
	}
	if !SamePlaybackAccess(access, again) {
		t.Fatal("metadata-only edit changed playback evidence")
	}
	sourceFile, err := store.GetSourceFile(ctx, 7, binding.Source.ID, []byte("verified-native"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: sourceFile.ObjectKey, Locator: sourceFile.Locator, SourceVersion: "replacement", SizeBytes: sourceFile.SizeBytes, ExpectedRevision: sourceFile.Revision}); err != nil {
		t.Fatal(err)
	}
	changed, err := store.ResolvePlayback(ctx, 8, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || changed.FileRevision != access.FileRevision+1 || changed.SourceVersion != "replacement" {
		t.Fatal(changed, err)
	}
	if SamePlaybackAccess(access, changed) {
		t.Fatal("source replacement kept lease evidence")
	}
	if _, err := store.db.Exec("UPDATE source SET authorization_revision=authorization_revision+1 WHERE id=?", binding.Source.ID); err != nil {
		t.Fatal(err)
	}
	changed, err = store.ResolvePlayback(ctx, 8, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || changed.Source.AuthorizationRevision != binding.Source.AuthorizationRevision+1 || changed.Binding.AuthorizationRevision != changed.Source.AuthorizationRevision {
		t.Fatal(changed, err)
	}
	revoked, err := store.RevokeMember(ctx, 7, library.ID, grant.LibraryRevision, 8)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolvePlayback(ctx, 8, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	regrant, err := store.GrantMember(ctx, 7, library.ID, revoked.LibraryRevision, 8, RoleViewer)
	if err != nil {
		t.Fatal(err)
	}
	again, err = store.ResolvePlayback(ctx, 8, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || again.MembershipRevision != regrant.Revision || again.MembershipRevision == access.MembershipRevision {
		t.Fatal(again, err)
	}
	archived, err := store.ArchiveLibrary(ctx, 7, library.ID, regrant.LibraryRevision)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.RestoreLibrary(ctx, 7, library.ID, archived.Revision); err != nil {
		t.Fatal(err)
	}
	restored, err := store.ResolvePlayback(ctx, 8, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || restored.LibraryAccessRevision != access.LibraryAccessRevision+2 {
		t.Fatal(restored, err)
	}
	if SamePlaybackAccess(again, restored) {
		t.Fatal("archive and restore revived old playback evidence")
	}
}

func TestPlaybackMappingRevisionFencesMoveAndABA(t *testing.T) {
	store, library, binding, _, file, variant := playbackFixture(t)
	ctx := t.Context()
	if _, err := store.db.Exec("UPDATE variant SET id='changed-variant' WHERE id=?", variant.VariantID); err == nil {
		t.Fatal("mutable variant ID accepted")
	}
	if _, err := store.db.Exec("UPDATE edition SET id='changed-edition' WHERE id=?", variant.EditionID); err == nil {
		t.Fatal("mutable edition ID accepted")
	}
	if _, err := store.db.Exec("UPDATE variant SET edition_id=edition_id WHERE id=?", variant.VariantID); err != nil {
		t.Fatal(err)
	}
	unchanged, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || unchanged.MappingRevision != 1 {
		t.Fatal(unchanged, err)
	}
	other, err := store.CreateCatalogItem(ctx, 7, library.ID, NewCatalogItem{Kind: "video", Title: "Other"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`INSERT INTO edition(id,library_id,item_id,name) VALUES ('edition-other',?,?, 'Other cut')`, library.ID, file.ItemID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec("UPDATE variant SET edition_id='edition-other' WHERE id=?", variant.VariantID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	moved, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, "edition-other", variant.VariantID, binding.ID)
	if err != nil || moved.MappingRevision != 2 {
		t.Fatal(moved, err)
	}
	if _, err := store.db.Exec("UPDATE variant SET edition_id=? WHERE id=?", variant.EditionID, variant.VariantID); err != nil {
		t.Fatal(err)
	}
	back, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || back.MappingRevision != 3 {
		t.Fatal(back, err)
	}
	if _, err := store.db.Exec("UPDATE edition SET item_id=? WHERE id=?", other.ID, variant.EditionID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	// Moving an edition does not by itself authorize the destination item.
	if _, err := store.ResolvePlayback(ctx, 7, library.ID, other.ID, variant.EditionID, variant.VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("destination accessible before mapping", err)
	}
	if _, err := store.db.Exec(`UPDATE file_item_mapping SET item_id=?,mapping_revision=mapping_revision+1 WHERE variant_id=? AND active=1`, other.ID, variant.VariantID); err != nil {
		t.Fatal(err)
	}
	otherAccess, err := store.ResolvePlayback(ctx, 7, library.ID, other.ID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || otherAccess.EditionMappingRevision != 2 {
		t.Fatal(otherAccess, err)
	}
	if _, err := store.db.Exec("UPDATE edition SET item_id=? WHERE id=?", file.ItemID, variant.EditionID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("ABA restored access before mapping", err)
	}
	if _, err := store.db.Exec(`UPDATE file_item_mapping SET item_id=?,mapping_revision=mapping_revision+1 WHERE variant_id=? AND active=1`, file.ItemID, variant.VariantID); err != nil {
		t.Fatal(err)
	}
	back, err = store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || back.EditionMappingRevision != 3 {
		t.Fatal(back, err)
	}
}

func TestPlaybackEvidenceFiltersOldProofAndListsBindings(t *testing.T) {
	store, library, binding, _, file, variant := playbackFixture(t)
	ctx := t.Context()
	other, err := store.BindLocalSource(ctx, 7, library.ID, binding.LibraryRevision, binding.Source.ID, []byte("/videos/other"))
	if err != nil {
		t.Fatal(err)
	}
	other = testAttestBinding(t, store, other)
	existing, err := store.GetSourceFile(ctx, 7, binding.Source.ID, []byte("verified-native"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.ObserveSourceFile(ctx, 7, other.Ref(), FileObservation{ObjectKey: existing.ObjectKey, Locator: []byte("/videos/other/one.mkv"), SourceVersion: existing.SourceVersion, SizeBytes: existing.SizeBytes, ExpectedRevision: existing.Revision}); err != nil {
		t.Fatal(err)
	}
	before, err := store.ListPlaybackAccess(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, "", 10)
	if err != nil || len(before.Items) != 1 || before.Items[0].BindingID != binding.ID {
		t.Fatal("observation unexpectedly created a second mapping", before, err)
	}
	item, err := store.GetCatalogItem(ctx, 7, library.ID, file.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.AttachCatalogFile(ctx, 7, library.ID, file.ItemID, item.Revision, file.SourceFileID); err != nil {
		t.Fatal(err)
	}
	page, err := store.ListPlaybackAccess(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, "", 1)
	if err != nil || len(page.Items) != 1 || page.NextCursor == "" {
		t.Fatal(page, err)
	}
	last, err := store.ListPlaybackAccess(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, page.NextCursor, 1)
	if err != nil || len(last.Items) != 1 || last.Items[0].BindingID == page.Items[0].BindingID || last.NextCursor != "" {
		t.Fatal(last, err)
	}
	if _, err := store.AttestSourceBindingWithOperation(ctx, 7, binding.ID, binding.Revision, "rebind-for-access", proofForTest(8)); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, other.ID); err != nil {
		t.Fatal(err)
	}
}

func TestPlaybackLocatorRevisionIsBindingLocalAndFencesABA(t *testing.T) {
	store, library, binding, _, file, variant := playbackFixture(t)
	ctx := t.Context()
	before, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil {
		t.Fatal(err)
	}
	other, err := store.BindLocalSource(ctx, 7, library.ID, binding.LibraryRevision, binding.Source.ID, []byte("/videos/other"))
	if err != nil {
		t.Fatal(err)
	}
	other = testAttestBinding(t, store, other)
	fileState, err := store.GetSourceFile(ctx, 7, binding.Source.ID, before.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	otherState, err := store.ObserveSourceFile(ctx, 7, other.Ref(), FileObservation{ObjectKey: before.ObjectKey, Locator: []byte("/videos/other/one.mkv"), SourceVersion: fileState.SourceVersion, SizeBytes: fileState.SizeBytes, ExpectedRevision: fileState.Revision})
	if err != nil {
		t.Fatal(err)
	}
	if otherState.Revision != before.FileRevision {
		t.Fatal("unrelated binding changed file revision", otherState)
	}
	version, err := store.GetObservationRevision(ctx, 7, binding.Ref(), before.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	first, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: before.ObjectKey, Locator: before.Locator, SourceVersion: before.SourceVersion, SizeBytes: fileState.SizeBytes, ExpectedRevision: version.FileRevision, ExpectedLocatorRevision: &version.LocatorRevision})
	if err != nil {
		t.Fatal(err)
	}
	afterOther, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || !SamePlaybackAccess(before, afterOther) {
		t.Fatal("unrelated binding invalidated lease", afterOther, err)
	}
	version = FileObservationRevision{FileRevision: first.Revision, LocatorRevision: first.LocatorRevision}
	moved, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: before.ObjectKey, Locator: []byte("/videos/renamed.mkv"), SourceVersion: before.SourceVersion, SizeBytes: fileState.SizeBytes, ExpectedRevision: version.FileRevision, ExpectedLocatorRevision: &version.LocatorRevision})
	if err != nil {
		t.Fatal(err)
	}
	version.LocatorRevision = moved.LocatorRevision
	returned, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: before.ObjectKey, Locator: before.Locator, SourceVersion: before.SourceVersion, SizeBytes: fileState.SizeBytes, ExpectedRevision: version.FileRevision, ExpectedLocatorRevision: &version.LocatorRevision})
	if err != nil {
		t.Fatal(err)
	}
	if returned.Revision != before.FileRevision || returned.LocatorRevision != before.LocatorRevision+2 {
		t.Fatal(returned)
	}
	afterABA, err := store.ResolvePlayback(ctx, 7, library.ID, file.ItemID, variant.EditionID, variant.VariantID, binding.ID)
	if err != nil || SamePlaybackAccess(before, afterABA) {
		t.Fatal("locator ABA revived lease", afterABA, err)
	}
	stale := before.LocatorRevision
	if _, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: before.ObjectKey, Locator: before.Locator, SourceVersion: before.SourceVersion, SizeBytes: fileState.SizeBytes, ExpectedRevision: before.FileRevision, ExpectedLocatorRevision: &stale}); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal("stale binding locator accepted", err)
	}
}

func TestLegacyObserveRequiresTokenForLocatorChanges(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	file := observeFile(t, store, binding, "one.mkv")
	ctx := t.Context()
	if _, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: file.Locator, SourceVersion: file.SourceVersion, ExpectedRevision: file.Revision}); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: []byte("/videos/moved.mkv"), SourceVersion: file.SourceVersion, ExpectedRevision: file.Revision}); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	zero := int64(0)
	if _, err := store.ObserveSourceFile(ctx, 7, binding.Ref(), FileObservation{ObjectKey: file.ObjectKey, Locator: file.Locator, SourceVersion: file.SourceVersion, ExpectedRevision: file.Revision, ExpectedLocatorRevision: &zero}); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal("zero skipped an existing binding", err)
	}
	loaded, err := store.GetSourceFile(ctx, 7, binding.Source.ID, file.ObjectKey)
	if err != nil || !bytes.Equal(loaded.Locator, file.Locator) || loaded.Revision != file.Revision {
		t.Fatal(loaded, err)
	}
}

func TestPlaybackVariantsUseBoundedOpaquePagination(t *testing.T) {
	store, library, binding, _, file, _ := playbackFixture(t)
	ctx := t.Context()
	second := observeFile(t, store, binding, "second.mkv")
	item, err := store.GetCatalogItem(ctx, 7, library.ID, file.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.AttachCatalogFile(ctx, 7, library.ID, item.ID, item.Revision, second.ID); err != nil {
		t.Fatal(err)
	}
	first, err := store.ListPlaybackVariants(ctx, 7, library.ID, file.ItemID, "", 1)
	if err != nil || len(first.Items) != 1 || first.NextCursor == "" || first.Items[0].Cursor != first.NextCursor {
		t.Fatal(first, err)
	}
	last, err := store.ListPlaybackVariants(ctx, 7, library.ID, file.ItemID, first.NextCursor, 1)
	if err != nil || len(last.Items) != 1 || last.NextCursor != "" || first.Items[0].VariantID == last.Items[0].VariantID {
		t.Fatal(last, err)
	}
	if _, err := store.ListPlaybackVariants(ctx, 7, library.ID, file.ItemID, "not-a-valid-cursor", 1); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	if _, err := store.ListPlaybackVariants(ctx, 8, library.ID, file.ItemID, "", 10); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestArtworkEvidenceIsPrivateAndFencesUndoABA(t *testing.T) {
	store, library, binding, _, file, _ := playbackFixture(t)
	ctx := t.Context()
	detail, err := store.GetItemDetail(ctx, 7, library.ID, file.ItemID)
	if err != nil || len(detail.Artwork) != 1 {
		t.Fatal(detail, err)
	}
	artID := detail.Artwork[0].ID
	if _, err := store.db.Exec("UPDATE artwork_declaration SET id='changed-art' WHERE id=?", artID); err == nil {
		t.Fatal("mutable artwork ID accepted")
	}
	access, err := store.ResolveArtwork(ctx, 7, library.ID, file.ItemID, artID)
	if err != nil || string(access.Reference) != "poster.jpg" || string(access.SourceKey) != "/videos/one.nfo" || access.Source.OwnerUserID != 7 || access.DeclarationRevision != 1 {
		t.Fatal(access, err)
	}
	encoded, err := json.Marshal(access)
	if err != nil || strings.Contains(string(encoded), "poster.jpg") || strings.Contains(string(encoded), "/videos/") {
		t.Fatal(string(encoded), err)
	}
	mutation, err := store.ApplyMetadata(ctx, 7, library.ID, file.ItemID, detail.Item.Revision, "art-title", MetadataPatch{Fields: map[string]MetadataFieldPatch{"title": {Values: []string{"Manual"}}}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.UndoMetadata(ctx, 7, library.ID, file.ItemID, mutation.ItemRevision, mutation.MetadataRevision, "undo-title"); err != nil {
		t.Fatal(err)
	}
	again, err := store.ResolveArtwork(ctx, 7, library.ID, file.ItemID, artID)
	if err != nil || again.DeclarationRevision != access.DeclarationRevision {
		t.Fatal(again, err)
	}
	if !SameArtworkAccess(access, again) {
		t.Fatal("metadata-only undo changed artwork evidence")
	}
	if _, err := store.db.Exec("UPDATE artwork_declaration SET active=0 WHERE id=?", artID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolveArtwork(ctx, 7, library.ID, file.ItemID, artID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.db.Exec("UPDATE artwork_declaration SET active=1 WHERE id=?", artID); err != nil {
		t.Fatal(err)
	}
	again, err = store.ResolveArtwork(ctx, 7, library.ID, file.ItemID, artID)
	if err != nil || again.DeclarationRevision != access.DeclarationRevision+2 {
		t.Fatal(again, err)
	}
	if SameArtworkAccess(access, again) {
		t.Fatal("artwork ABA kept lease evidence")
	}
	if _, err := store.ResolveArtwork(ctx, 8, library.ID, file.ItemID, artID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	archived, err := store.ArchiveLibrary(ctx, 7, library.ID, binding.LibraryRevision)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolveArtwork(ctx, 7, library.ID, file.ItemID, artID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.RestoreLibrary(ctx, 7, library.ID, archived.Revision); err != nil {
		t.Fatal(err)
	}
	restored, err := store.ResolveArtwork(ctx, 7, library.ID, file.ItemID, artID)
	if err != nil || restored.LibraryAccessRevision != access.LibraryAccessRevision+2 {
		t.Fatal(restored, err)
	}
	if SameArtworkAccess(again, restored) {
		t.Fatal("archive and restore revived old artwork evidence")
	}
	if _, err := store.AttestSourceBindingWithOperation(ctx, 7, binding.ID, binding.Revision, "art-rebind", proofForTest(6)); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolveArtwork(ctx, 7, library.ID, file.ItemID, artID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestVersionSixMappingMigrationPreservesExistingRows(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v6.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	old := &Store{db: db}
	err = old.WithTx(context.Background(), func(tx *sql.Tx) error {
		if _, err := tx.Exec(schemaV1 + schemaV2 + schemaV3 + schemaV4 + schemaV5BeforeIDs + schemaV5AfterIDs + schemaV6); err != nil {
			return err
		}
		if err := addLibrary(context.Background(), tx, "old"); err != nil {
			return err
		}
		_, err := tx.Exec(`INSERT INTO library_item(id,library_id,kind,title,created_at) VALUES ('item','old','video','Title',1);
INSERT INTO edition(id,library_id,item_id,name,is_default) VALUES ('edition','old','item','',1);
INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES ('source',7,'local','config',1);
INSERT INTO library_source(library_id,source_id,root_locator,binding_id) VALUES ('old','source',X'2F','bind_old');
INSERT INTO source_file(id,source_id,object_key,locator,source_version,observed_at) VALUES ('file','source',X'FF',X'2FFF','v1',1);
INSERT INTO library_file(library_id,source_id,root_locator,source_file_id,locator,observed_at,binding_epoch) VALUES ('old','source',X'2F','file',X'2FFF',1,0);
INSERT INTO variant(id,library_id,edition_id,source_file_id) VALUES ('variant','old','edition','file');
INSERT INTO job(id,library_id,owner_user_id,kind,status,created_at,updated_at) VALUES ('job','old',7,'library.scan','running',1,1);
INSERT INTO scan_job(job_id,library_id,source_id,root_locator,binding_revision,authorization_revision,rules_revision,operation_id,active,binding_id,binding_epoch) VALUES ('job','old','source',X'2F',1,1,1,'op',1,'bind_old',0);
INSERT INTO scan_missing_candidate(job_id,generation,source_file_id,file_revision,locator) VALUES ('job',1,'file',1,X'2FFF');` + fmt.Sprintf("PRAGMA application_id=%d; PRAGMA user_version=6", applicationID))
		return err
	})
	if closeErr := old.Close(); err != nil || closeErr != nil {
		t.Fatal(err, closeErr)
	}
	store, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	}()
	var editionRev, variantRev int64
	var key []byte
	if err := store.db.QueryRow(`SELECT e.mapping_revision,v.mapping_revision,f.object_key FROM edition e JOIN variant v ON v.edition_id=e.id JOIN source_file f ON f.id=v.source_file_id WHERE e.id='edition'`).Scan(&editionRev, &variantRev, &key); err != nil || editionRev != 1 || variantRev != 1 || !bytes.Equal(key, []byte{0xff}) {
		t.Fatal(editionRev, variantRev, key, err)
	}
	var locator []byte
	var locatorRevision int64
	if err := store.db.QueryRow("SELECT locator,locator_revision FROM library_file WHERE source_file_id='file'").Scan(&locator, &locatorRevision); err != nil || !bytes.Equal(locator, []byte{'/', 0xff}) || locatorRevision != 1 {
		t.Fatal(locator, locatorRevision, err)
	}
	if err := store.db.QueryRow("SELECT locator,locator_revision FROM scan_missing_candidate WHERE source_file_id='file'").Scan(&locator, &locatorRevision); err != nil || !bytes.Equal(locator, []byte{'/', 0xff}) || locatorRevision != 1 {
		t.Fatal(locator, locatorRevision, err)
	}
	var violations int
	if err := store.db.QueryRow("SELECT count(*) FROM pragma_foreign_key_check").Scan(&violations); err != nil || violations != 0 {
		t.Fatal(violations, err)
	}
}
