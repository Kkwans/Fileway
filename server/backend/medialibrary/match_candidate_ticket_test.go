package medialibrary

import (
	"errors"
	"testing"
	"time"
)

func TestMatchCandidateTicketMembershipExpiryAndRevisionFence(t *testing.T) {
	store := openTestStore(t)
	ctx := t.Context()
	library := createLibrary(t, store, 7, "候选票据")
	connection := fixtureProvider(t, store, 7, false, "user", "ticket-connection")
	if _, err := store.SetLibraryMetadataPolicy(ctx, 7, library.ID, "ticket-policy", 0,
		LibraryMetadataPolicy{Mode: MetadataLocalFirst, ConnectionID: connection.ID, Language: "zh-CN", Region: "CN"}); err != nil {
		t.Fatal(err)
	}
	authority, err := store.CheckProviderUse(ctx, 7, library.ID, connection.ID)
	if err != nil {
		t.Fatal(err)
	}
	item := catalogItem(t, store, 7, library.ID, "本地标题")
	var structureRevision int64
	if err := store.db.QueryRow(`SELECT structure_revision FROM item_structure WHERE library_id=? AND item_id=?`, library.ID, item.ID).Scan(&structureRevision); err != nil {
		t.Fatal(err)
	}
	setID, err := store.SaveMatchCandidates(ctx, 7, *authority, MatchCandidateSearch{LibraryID: library.ID, ItemID: item.ID,
		ConnectionID: connection.ID, Kind: "movie", Title: "回声", Year: 2020, Page: 1, ItemRevision: item.Revision,
		StructureRevision: structureRevision, Language: "zh-CN", Region: "CN", RulesVersion: "tmdb-match-v1"},
		[]MatchCandidateRecord{{Provider: "tmdb", Kind: "movie", ExternalID: 42, Title: "回声", Year: 2020, Evidence: []string{"title_match", "year_match"}}})
	if err != nil {
		t.Fatal(err)
	}
	request := ProviderMatchRequest{LibraryID: library.ID, ItemID: item.ID, ConnectionID: connection.ID,
		Kind: "movie", ExternalID: 42, ItemRevision: item.Revision, StructureRevision: structureRevision}
	resolved, err := store.ResolveMatchCandidate(ctx, 7, *authority, request, setID)
	if err != nil || resolved != setID {
		t.Fatalf("ticket resolution: id=%s err=%v", resolved, err)
	}
	request.ExternalID = 43
	if _, err := store.ResolveMatchCandidate(ctx, 7, *authority, request, setID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("outside candidate accepted: %v", err)
	}
	request.ExternalID = 42
	if _, err := store.db.Exec(`UPDATE provider_match_candidate_set SET expires_at=? WHERE id=?`, time.Now().UnixMilli()-1, setID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolveMatchCandidate(ctx, 7, *authority, request, setID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired ticket accepted: %v", err)
	}
	if _, err := store.db.Exec(`UPDATE provider_match_candidate_set SET expires_at=? WHERE id=?`, time.Now().Add(time.Minute).UnixMilli(), setID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.Exec(`UPDATE library_item SET revision=revision+1 WHERE library_id=? AND id=?`, library.ID, item.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ResolveMatchCandidate(ctx, 7, *authority, request, setID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("stale item ticket accepted: %v", err)
	}
}
