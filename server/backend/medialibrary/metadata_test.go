package medialibrary

import (
	"bytes"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

func testNFO(t *testing.T, text string) *MetadataObservation {
	t.Helper()
	document, err := mediametadata.ParseNFO(strings.NewReader(text), mediametadata.Limits{})
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256([]byte(text))
	return &MetadataObservation{SourceKey: []byte("/videos/one.nfo"), Digest: digest[:], ParserVersion: 1, Document: *document}
}

func scanNFO(t *testing.T, store *Store, binding *LibrarySource, lease *ScanLease, input ScanObservation, sequence int64) *ScanCommit {
	t.Helper()
	current, err := store.GetObservationRevision(t.Context(), binding.Source.OwnerUserID, binding.Ref(), input.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	input.ExpectedRevision, input.ExpectedLocatorRevision = current.FileRevision, current.LocatorRevision
	result, err := store.CommitScanObservation(t.Context(), binding.Source.OwnerUserID, *lease, input, testCheckpoint(input, sequence, sequence))
	if err != nil {
		t.Fatal(err)
	}
	return result
}

func TestNFOScanLocksResetAndUnchangedReprojection(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "nfo")
	input := testObservation("native-nfo", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>NFO title</title><plot>Story</plot><uniqueid type="imdb">tt123</uniqueid><ratings><rating name="douban" max="10"><value>8.1</value><votes>1,000+</votes></rating></ratings><actor><name>Person</name><role>Lead</role><uniqueid type="tmdb">42</uniqueid></actor><thumb aspect="poster">poster.jpg</thumb></movie>`)
	first := scanNFO(t, store, binding, lease, input, 1)
	detail, err := store.GetItemDetail(t.Context(), 7, library.ID, first.ItemID)
	if err != nil || detail.Item.Title != "NFO title" || detail.Fields["plot"].Values[0] != "Story" || len(detail.Credits) != 1 || len(detail.Ratings) != 1 || len(detail.Artwork) != 1 || len(detail.ExternalIDs) != 1 {
		t.Fatal(detail, err)
	}
	if detail.Fields["title"].InputID == "" || detail.Credits[0].InputID != detail.Fields["title"].InputID {
		t.Fatal("missing provenance", detail)
	}
	if detail.Ratings[0].Rating.Provider != "douban" || detail.Ratings[0].Rating.Value == nil || *detail.Ratings[0].Rating.Value != 8.1 || detail.Ratings[0].Rating.Count == nil || *detail.Ratings[0].Rating.Count != 1000 || detail.Ratings[0].Rating.CountPrecision != "lower_bound" || detail.Ratings[0].Rating.CountKind != "user_ratings" {
		t.Fatal(detail.Ratings)
	}
	initialRevision, metaRevision := detail.Item.Revision, detail.MetadataRevision
	scanNFO(t, store, binding, lease, input, 2)
	detail, err = store.GetItemDetail(t.Context(), 7, library.ID, first.ItemID)
	if err != nil || detail.Item.Revision != initialRevision || detail.MetadataRevision != metaRevision {
		t.Fatal(detail, err)
	}
	manual, err := store.ApplyMetadata(t.Context(), 7, library.ID, first.ItemID, detail.Item.Revision, "manual", MetadataPatch{Fields: map[string]MetadataFieldPatch{"title": {Values: []string{"Manual"}}}})
	if err != nil {
		t.Fatal(err)
	}
	scanNFO(t, store, binding, lease, input, 3)
	detail, err = store.GetItemDetail(t.Context(), 7, library.ID, first.ItemID)
	if err != nil || detail.Item.Title != "Manual" || !detail.Fields["title"].Locked || detail.Fields["title"].InputID != "" {
		t.Fatal(detail, err)
	}
	unlocked := false
	unlock, err := store.ApplyMetadata(t.Context(), 7, library.ID, first.ItemID, manual.ItemRevision, "unlock", MetadataPatch{Fields: map[string]MetadataFieldPatch{"title": {Locked: &unlocked}}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.ApplyMetadata(t.Context(), 7, library.ID, first.ItemID, manual.ItemRevision, "unlock", MetadataPatch{Fields: map[string]MetadataFieldPatch{"title": {Values: []string{}, Locked: &unlocked}}}); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	scanNFO(t, store, binding, lease, input, 4)
	detail, err = store.GetItemDetail(t.Context(), 7, library.ID, first.ItemID)
	if err != nil || detail.Item.Title != "NFO title" || detail.Item.Revision <= unlock.ItemRevision {
		t.Fatal(detail, err)
	}
	cleared, err := store.ApplyMetadata(t.Context(), 7, library.ID, first.ItemID, detail.Item.Revision, "clear", MetadataPatch{Fields: map[string]MetadataFieldPatch{"title": {Clear: true}}})
	if err != nil {
		t.Fatal(err)
	}
	scanNFO(t, store, binding, lease, input, 5)
	detail, err = store.GetItemDetail(t.Context(), 7, library.ID, first.ItemID)
	if err != nil || detail.Item.Title != "" || !detail.Fields["title"].ExplicitClear {
		t.Fatal(detail, err)
	}
	if _, err := store.ApplyMetadata(t.Context(), 7, library.ID, first.ItemID, cleared.ItemRevision, "reset", MetadataPatch{Fields: map[string]MetadataFieldPatch{"title": {Reset: true}}}); err != nil {
		t.Fatal(err)
	}
	scanNFO(t, store, binding, lease, input, 6)
	detail, err = store.GetItemDetail(t.Context(), 7, library.ID, first.ItemID)
	if err != nil || detail.Item.Title != "NFO title" || detail.Fields["title"].ExplicitClear {
		t.Fatal(detail, err)
	}
	access, err := store.ListItemMetadataSourceAccess(t.Context(), 7, library.ID, first.ItemID, "", 100)
	if err != nil || len(access.Items) != 1 || !bytes.Equal(access.Items[0].SourceKey, input.NFO.SourceKey) {
		t.Fatal(access, err)
	}
	encoded, err := json.Marshal(detail)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "/videos/") || strings.Contains(string(encoded), "poster.jpg") || strings.Contains(string(encoded), detail.Fields["title"].InputID) {
		t.Fatal("private provenance escaped", string(encoded))
	}
}

func TestNFOMetadataUndoRestoresAllProjectionsAndKeepsEvidence(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "undo")
	input := testObservation("native-undo", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>First</title><plot>Retained</plot><ratings><rating name="imdb"><value>7.2</value><votes>123</votes></rating></ratings><actor><name>A</name></actor><thumb aspect="poster">a.jpg</thumb></movie>`)
	first := scanNFO(t, store, binding, lease, input, 1)
	before, err := store.GetItemDetail(t.Context(), 7, library.ID, first.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	input.NFO = testNFO(t, `<movie><title>Second</title><ratings><rating name="douban"><value>8.3</value><votes>500</votes></rating></ratings><actor><name>B</name></actor><thumb aspect="poster">b.jpg</thumb></movie>`)
	scanNFO(t, store, binding, lease, input, 2)
	after, err := store.GetItemDetail(t.Context(), 7, library.ID, first.ItemID)
	if err != nil || after.Item.Title != "Second" || after.Fields["plot"].Values[0] != "Retained" || after.Credits[0].Name != "B" || after.Ratings[0].Rating.Provider != "douban" {
		t.Fatal(after, err)
	}
	inputs, err := store.ListItemMetadataSourceAccess(t.Context(), 7, library.ID, first.ItemID, "", 100)
	if err != nil || len(inputs.Items) != 2 {
		t.Fatal(inputs, err)
	}
	if _, err := store.UndoMetadata(t.Context(), 7, library.ID, first.ItemID, after.Item.Revision, before.MetadataRevision, "wrong-target"); !errors.Is(err, ErrRevisionConflict) {
		t.Fatal(err)
	}
	ack, err := store.UndoMetadata(t.Context(), 7, library.ID, first.ItemID, after.Item.Revision, after.MetadataRevision, "undo-latest")
	if err != nil {
		t.Fatal(err)
	}
	restored, err := store.GetItemDetail(t.Context(), 7, library.ID, first.ItemID)
	if err != nil || restored.Item.Title != "First" || restored.Credits[0].ID != before.Credits[0].ID || restored.Ratings[0].ID != before.Ratings[0].ID || restored.Artwork[0].ID != before.Artwork[0].ID || restored.MetadataRevision != ack.MetadataRevision {
		t.Fatal(restored, err)
	}
	if tableCount(t, store, "metadata_input") != 2 || tableCount(t, store, "rating_observation") != 2 || tableCount(t, store, "artwork_declaration") != 2 {
		t.Fatal("history was removed")
	}
	if again, err := store.UndoMetadata(t.Context(), 7, library.ID, first.ItemID, after.Item.Revision, after.MetadataRevision, "undo-latest"); err != nil || *again != *ack {
		t.Fatal(again, err)
	}
}

func TestNFOCandidatesAndOutboxFailureRemainAtomic(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "candidate")
	input := testObservation("candidate-key", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>Not projected</title><uniqueid type="imdb">tt1</uniqueid><uniqueid type="imdb">tt2</uniqueid></movie>`)
	if _, err := store.db.Exec(`CREATE TRIGGER fail_metadata_event BEFORE INSERT ON outbox WHEN NEW.kind='catalog.item.metadata.candidate' BEGIN SELECT RAISE(ABORT,'injected'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := store.CommitScanObservation(t.Context(), 7, *lease, input, testCheckpoint(input, 1, 1)); err == nil {
		t.Fatal("expected rollback")
	}
	for _, table := range []string{"source_file", "library_file", "library_item", "metadata_input"} {
		if got := tableCount(t, store, table); got != 0 {
			t.Fatalf("%s=%d", table, got)
		}
	}
	if _, err := store.db.Exec("DROP TRIGGER fail_metadata_event"); err != nil {
		t.Fatal(err)
	}
	result := scanNFO(t, store, binding, lease, input, 1)
	detail, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || detail.Item.Title != "Example" || len(detail.Issues) != 1 || detail.Issues[0] != "external_id_conflict" || len(detail.IssueSources) != 1 {
		t.Fatal(detail, err)
	}
	input.NFO = testNFO(t, `<episodedetails><title>A</title></episodedetails><episodedetails><title>B</title></episodedetails>`)
	scanNFO(t, store, binding, lease, input, 2)
	detail, err = store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || detail.Item.Title != "Example" || len(detail.Issues) != 1 || detail.Issues[0] != "multiple_items" {
		t.Fatal(detail, err)
	}
}

func TestCatalogTitleAllowsLongFilenameWithoutChangingLocator(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "long")
	input := testObservation("long-key", "/videos/original.mkv")
	input.InitialTitle = strings.Repeat("长", 250)
	result := scanNFO(t, store, binding, lease, input, 1)
	item, err := store.GetCatalogItem(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || item.Title != input.InitialTitle {
		t.Fatal(item, err)
	}
	file, err := store.GetSourceFile(t.Context(), 7, binding.Source.ID, input.ObjectKey)
	if err != nil || !bytes.Equal(file.Locator, input.Locator) {
		t.Fatal(file, err)
	}
}

func TestMetadataCreditsDoNotMergeNamesAndExplicitIDsCanLink(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "people")
	input := testObservation("people-key", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>First</title><actor><name>Same</name><role>A</role></actor><actor><name>Same</name><role>B</role></actor><actor><name>Known</name><uniqueid type="tmdb">10</uniqueid></actor><actor><name>Known alias</name><uniqueid type="tmdb">10</uniqueid></actor></movie>`)
	result := scanNFO(t, store, binding, lease, input, 1)
	first, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || len(first.Credits) != 4 || first.Credits[0].PersonID == first.Credits[1].PersonID || first.Credits[2].PersonID != first.Credits[3].PersonID {
		t.Fatal(first, err)
	}
	input.NFO = testNFO(t, `<movie><title>Changed</title><actor><name>Same</name><role>A</role></actor><actor><name>Same</name><role>B</role></actor><actor><name>Known</name><uniqueid type="tmdb">10</uniqueid></actor><actor><name>Known alias</name><uniqueid type="tmdb">10</uniqueid></actor></movie>`)
	scanNFO(t, store, binding, lease, input, 2)
	second, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || len(second.Credits) != 4 {
		t.Fatal(second, err)
	}
	for i := range first.Credits {
		if first.Credits[i].PersonID != second.Credits[i].PersonID {
			t.Fatal("person identity drifted", first.Credits, second.Credits)
		}
	}
	input.NFO = testNFO(t, `<movie><title>Conflict</title><actor><name>One</name><uniqueid type="imdb">nm1</uniqueid><uniqueid type="tmdb">10</uniqueid></actor><actor><name>Two</name><uniqueid type="imdb">nm2</uniqueid><uniqueid type="tmdb">10</uniqueid></actor></movie>`)
	scanNFO(t, store, binding, lease, input, 3)
	candidate, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || candidate.Item.Title != "Changed" || len(candidate.Issues) != 1 || candidate.Issues[0] != "person_id_conflict" {
		t.Fatal(candidate, err)
	}
}

func TestEmptyNFOElementsPreserveExistingAndRemoteArtworkIsRedacted(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "empty")
	input := testObservation("empty-key", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>Keep</title><plot>Story</plot><actor><name>Actor</name></actor><ratings><rating name="imdb"><value>8</value></rating></ratings><thumb aspect="poster">https://user:secret@example.test/image?token=secret</thumb></movie>`)
	result := scanNFO(t, store, binding, lease, input, 1)
	before, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || len(before.Artwork) != 1 || before.Artwork[0].Status != "remote_unavailable" {
		t.Fatal(before, err)
	}
	var evidence string
	var privateRef []byte
	if err := store.db.QueryRow("SELECT evidence_json FROM metadata_input WHERE item_id=?", result.ItemID).Scan(&evidence); err != nil {
		t.Fatal(err)
	}
	if err := store.db.QueryRow("SELECT private_ref FROM artwork_declaration WHERE item_id=?", result.ItemID).Scan(&privateRef); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(evidence, "secret") || strings.Contains(evidence, "example.test") || len(privateRef) != 0 {
		t.Fatal("remote artwork credentials persisted", evidence)
	}
	input.NFO = testNFO(t, `<movie><title></title><plot></plot><actor></actor><ratings><rating name="imdb"><value></value></rating></ratings><thumb></thumb></movie>`)
	scanNFO(t, store, binding, lease, input, 2)
	after, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || after.Item.Title != "Keep" || after.Fields["plot"].Values[0] != "Story" || after.Credits[0].ID != before.Credits[0].ID || after.Ratings[0].ID != before.Ratings[0].ID || after.Artwork[0].ID != before.Artwork[0].ID {
		t.Fatal(after, err)
	}
}

func TestMetadataContextCandidatesAndRevokedViewer(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "context")
	input := testObservation("context-key", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<tvshow><title>Series title</title></tvshow>`)
	result := scanNFO(t, store, binding, lease, input, 1)
	detail, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || detail.Item.Title != "Example" || len(detail.IssueSources) != 1 || detail.IssueSources[0].Code != "context_required" || len(detail.IssueSources[0].SourceKinds) != 1 || detail.IssueSources[0].SourceKinds[0] != "tvshow" {
		t.Fatal(detail, err)
	}
	grant, err := store.GrantMember(t.Context(), 7, library.ID, binding.LibraryRevision, 8, RoleViewer)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.GetItemDetail(t.Context(), 8, library.ID, result.ItemID); err != nil {
		t.Fatal(err)
	}
	files, err := store.ListItemSourceAccess(t.Context(), 8, library.ID, result.ItemID, "", 1)
	if err != nil || len(files.Items) != 1 || !bytes.Equal(files.Items[0].Locator, input.Locator) || files.Items[0].IdentityKind != "native" {
		t.Fatal(files, err)
	}
	sources, err := store.ListItemMetadataSourceAccess(t.Context(), 8, library.ID, result.ItemID, "", 1)
	if err != nil || len(sources.Items) != 1 || sources.Items[0].ID != detail.IssueSources[0].InputID {
		t.Fatal(sources, err)
	}
	if _, err := store.RevokeMember(t.Context(), 7, library.ID, grant.LibraryRevision, 8); err != nil {
		t.Fatal(err)
	}
	if _, err := store.GetItemDetail(t.Context(), 8, library.ID, result.ItemID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := store.ListItemSourceAccess(t.Context(), 8, library.ID, result.ItemID, "", 1); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestDirectoryNFOAssociationChangeReevaluatesSameDigest(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "association")
	input := testObservation("association-key", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>Directory evidence</title></movie>`)
	input.NFO.AssociationAmbiguous = true
	result := scanNFO(t, store, binding, lease, input, 1)
	candidate, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || candidate.Item.Title != "Example" || len(candidate.Issues) != 1 || candidate.Issues[0] != "ambiguous_association" {
		t.Fatal(candidate, err)
	}
	input.NFO.AssociationAmbiguous = false
	scanNFO(t, store, binding, lease, input, 2)
	resolved, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || resolved.Item.Title != "Directory evidence" || len(resolved.Issues) != 0 || resolved.Item.Revision <= candidate.Item.Revision {
		t.Fatal(resolved, err)
	}
}

func TestMetadataMigrationPreservesUnknownLegacyTitleAsLocked(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v5.db")
	db, err := sql.Open("sqlite", databaseURL(path, "rwc"))
	if err != nil {
		t.Fatal(err)
	}
	old := &Store{db: db}
	err = old.WithTx(t.Context(), func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(t.Context(), schemaV1+schemaV2+schemaV3+schemaV4+schemaV5BeforeIDs+schemaV5AfterIDs); err != nil {
			return err
		}
		if err := addLibrary(t.Context(), tx, "old"); err != nil {
			return err
		}
		if _, err := tx.ExecContext(t.Context(), `INSERT INTO library_item(id,library_id,kind,title,created_at) VALUES ('item','old','video','Earlier title',1);
INSERT INTO source(id,owner_user_id,kind,configuration_ref,created_at) VALUES ('source',7,'local','config',1);
INSERT INTO library_source(library_id,source_id,root_locator,binding_id) VALUES ('old','source',X'2F766964656F73','bind_old');
INSERT INTO source_file(id,source_id,object_key,locator,source_version,observed_at) VALUES ('file','source',X'01',X'2F766964656F732F6F6C64','v1',1);
INSERT INTO library_file(library_id,source_id,root_locator,source_file_id,locator,observed_at) VALUES ('old','source',X'2F766964656F73','file',X'2F766964656F732F6F6C64',1);
INSERT INTO edition(id,library_id,item_id,name,is_default) VALUES ('edition','old','item','',1);
INSERT INTO variant(id,library_id,edition_id,source_file_id) VALUES ('variant','old','edition','file')`); err != nil {
			return err
		}
		_, err := tx.ExecContext(t.Context(), fmt.Sprintf("PRAGMA application_id = %d; PRAGMA user_version = 5", applicationID))
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
	detail, err := store.GetItemDetail(t.Context(), 7, "old", "item")
	if err != nil || detail.Item.Title != "" || len(detail.LegacyFields) != 1 || detail.LegacyFields[0] != "title" || len(detail.Fields) != 0 {
		t.Fatal(detail, err)
	}
	var preserved string
	var locked int
	if err := store.db.QueryRow("SELECT value_json,locked FROM item_field WHERE library_id='old' AND item_id='item' AND field='title'").Scan(&preserved, &locked); err != nil || preserved != `["Earlier title"]` || locked != 1 {
		t.Fatal(preserved, locked, err)
	}
	page, err := store.ListItemSourceAccess(t.Context(), 7, "old", "item", "", 10)
	if err != nil || len(page.Items) != 0 {
		t.Fatal(page, err)
	}
	if _, err := store.AttestSourceBindingWithOperation(t.Context(), 7, "bind_old", 1, "confirm-old", proofForTest(8)); err != nil {
		t.Fatal(err)
	}
	page, err = store.ListItemSourceAccess(t.Context(), 7, "old", "item", "", 10)
	if err != nil || len(page.Items) != 0 {
		t.Fatal(page, err)
	}
	var epoch int64
	var missing *int64
	if err := store.db.QueryRow("SELECT binding_epoch,missing_since FROM library_file WHERE source_file_id='file'").Scan(&epoch, &missing); err != nil || epoch != 0 || missing != nil {
		t.Fatal(epoch, missing, err)
	}
}

func TestMetadataResourcePagesAreBoundedAndCarryOpaqueCursors(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "pages")
	var xml strings.Builder
	xml.WriteString("<movie><title>Long credits</title>")
	for i := 0; i < 25; i++ {
		fmt.Fprintf(&xml, `<actor><name>Person %d</name></actor><rating name="imdb">8</rating><thumb aspect="poster">poster%d.jpg</thumb><uniqueid type="provider%d">id%d</uniqueid>`, i, i, i, i)
	}
	xml.WriteString("</movie>")
	input := testObservation("pages-key", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, xml.String())
	result := scanNFO(t, store, binding, lease, input, 1)
	detail, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || len(detail.Credits) != 20 || len(detail.Ratings) != 20 || len(detail.Artwork) != 20 || len(detail.ExternalIDs) != 20 || detail.CreditsNextCursor == "" || detail.RatingsNextCursor == "" || detail.ArtworkNextCursor == "" || detail.ExternalIDsNextCursor == "" {
		t.Fatal(detail, err)
	}
	credits, err := store.ListItemCredits(t.Context(), 7, library.ID, result.ItemID, detail.CreditsNextCursor, 100)
	if err != nil || len(credits.Items) != 5 || credits.NextCursor != "" || credits.Items[0].Cursor == "" || credits.ItemRevision != detail.Item.Revision {
		t.Fatal(credits, err)
	}
	ratings, err := store.ListItemRatings(t.Context(), 7, library.ID, result.ItemID, detail.RatingsNextCursor, 100)
	if err != nil || len(ratings.Items) != 5 || ratings.Items[0].Cursor == "" {
		t.Fatal(ratings, err)
	}
	art, err := store.ListItemArtwork(t.Context(), 7, library.ID, result.ItemID, detail.ArtworkNextCursor, 100)
	if err != nil || len(art.Items) != 5 || art.Items[0].Cursor == "" {
		t.Fatal(art, err)
	}
	ids, err := store.ListItemExternalIDs(t.Context(), 7, library.ID, result.ItemID, detail.ExternalIDsNextCursor, 100)
	if err != nil || len(ids.Items) != 5 || ids.Items[0].Cursor == "" {
		t.Fatal(ids, err)
	}
	if _, err := store.ListItemCredits(t.Context(), 7, library.ID, result.ItemID, "", 101); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	if _, err := store.GetItemMetadataSourceAccess(t.Context(), 7, library.ID, result.ItemID, credits.Items[0].InputID); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 25; i++ {
		candidate := *input.NFO
		candidate.SourceKey = []byte(fmt.Sprintf("/videos/candidate-%02d.nfo", i))
		candidate.AssociationAmbiguous = true
		input.NFO = &candidate
		scanNFO(t, store, binding, lease, input, int64(i+2))
	}
	detail, err = store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || len(detail.IssueSources) != 20 || detail.IssuesNextCursor == "" {
		t.Fatal(detail, err)
	}
	issues, err := store.ListItemMetadataIssues(t.Context(), 7, library.ID, result.ItemID, detail.IssuesNextCursor, 100)
	if err != nil || len(issues.Items) != 5 || issues.Items[0].Cursor == "" {
		t.Fatal(issues, err)
	}
}

func TestOversizedMetadataIsCandidateAndManualPatchRollsBack(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "budget")
	input := testObservation("budget-key", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, "<movie><title>Huge</title><plot>"+strings.Repeat("x", 65530)+"</plot></movie>")
	result := scanNFO(t, store, binding, lease, input, 1)
	detail, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || detail.Item.Title != "Example" || len(detail.Issues) != 1 || detail.Issues[0] != "metadata_too_large" {
		t.Fatal(detail, err)
	}
	if _, err := store.ApplyMetadata(t.Context(), 7, library.ID, result.ItemID, detail.Item.Revision, "too-large", MetadataPatch{Fields: map[string]MetadataFieldPatch{"plot": {Values: []string{strings.Repeat("x", 65530)}}}}); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	after, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || after.Item.Revision != detail.Item.Revision || len(after.Fields) != len(detail.Fields) {
		t.Fatal(after, err)
	}
}

func TestOversizedEncodedEvidenceDoesNotAbortScan(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Films")
	binding := createBoundSource(t, store, library)
	_, lease := testScan(t, store, binding, "oversized-evidence")
	input := testObservation("oversized-evidence-key", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = &MetadataObservation{SourceKey: []byte("/videos/one.nfo"), Digest: bytes.Repeat([]byte{5}, 32), ParserVersion: 1, Document: mediametadata.Document{Items: []mediametadata.Item{{Kind: mediametadata.Movie, Fields: map[string][]string{"plot": {strings.Repeat("\u2028", 700000)}}}}}}
	result := scanNFO(t, store, binding, lease, input, 1)
	detail, err := store.GetItemDetail(t.Context(), 7, library.ID, result.ItemID)
	if err != nil || len(detail.Issues) != 1 || detail.Issues[0] != "metadata_too_large" || detail.Item.Title != "Example" {
		t.Fatal(detail, err)
	}
	var size int
	if err := store.db.QueryRow("SELECT length(CAST(evidence_json AS BLOB)) FROM metadata_input WHERE item_id=?", result.ItemID).Scan(&size); err != nil || size > 1024 {
		t.Fatal(size, err)
	}
}

func TestMetadataAndPersonIdentityStayWithinEachLibrary(t *testing.T) {
	store := openTestStore(t)
	firstLib := createLibrary(t, store, 7, "One")
	secondLib := createLibrary(t, store, 7, "Two")
	firstBinding := createBoundSource(t, store, firstLib)
	secondBinding := createBoundSource(t, store, secondLib)
	_, firstLease := testScan(t, store, firstBinding, "private-one")
	_, secondLease := testScan(t, store, secondBinding, "private-two")
	input := testObservation("same-object-key", "/videos/one.mkv")
	input.NFOStatus = "parsed"
	input.NFO = testNFO(t, `<movie><title>Common</title><actor><name>Person</name><uniqueid type="tmdb">42</uniqueid></actor></movie>`)
	first := scanNFO(t, store, firstBinding, firstLease, input, 1)
	second := scanNFO(t, store, secondBinding, secondLease, input, 1)
	left, err := store.GetItemDetail(t.Context(), 7, firstLib.ID, first.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	right, err := store.GetItemDetail(t.Context(), 7, secondLib.ID, second.ItemID)
	if err != nil {
		t.Fatal(err)
	}
	if left.Credits[0].PersonID == right.Credits[0].PersonID {
		t.Fatal("person identity crossed libraries")
	}
	if _, err := store.ApplyMetadata(t.Context(), 7, firstLib.ID, first.ItemID, left.Item.Revision, "private-change", MetadataPatch{Fields: map[string]MetadataFieldPatch{"title": {Values: []string{"Private title"}}}}); err != nil {
		t.Fatal(err)
	}
	right, err = store.GetItemDetail(t.Context(), 7, secondLib.ID, second.ItemID)
	if err != nil || right.Item.Title != "Common" {
		t.Fatal(right, err)
	}
}
