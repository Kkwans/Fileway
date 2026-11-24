package medialibrary

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

type MatchCandidateRecord struct {
	Provider      string
	Kind          string
	ExternalID    int64
	Title         string
	OriginalTitle string
	Year          int
	Evidence      []string
}

type MatchCandidateSearch struct {
	LibraryID, ItemID, ConnectionID, Kind string
	Title                                 string
	Year, Page                            int
	ItemRevision, StructureRevision       int64
	Language, Region, RulesVersion        string
}

func (store *Store) SaveMatchCandidates(ctx context.Context, actor uint, authority ProviderUseAuthority,
	search MatchCandidateSearch, candidates []MatchCandidateRecord) (string, error) {
	if actor == 0 || search.LibraryID == "" || search.ItemID == "" || search.ConnectionID != authority.ConnectionID ||
		search.Kind != "movie" && search.Kind != "series" || strings.TrimSpace(search.Title) == "" ||
		len(search.Title) > 512 || !utf8.ValidString(search.Title) || strings.ContainsAny(search.Title, "/\\") ||
		strings.IndexFunc(search.Title, unicode.IsControl) >= 0 || search.Year < 0 || search.Year > 9999 ||
		search.Page < 1 || search.Page > 500 || search.ItemRevision < 1 || search.StructureRevision < 1 ||
		len(candidates) > 100 || search.RulesVersion == "" {
		return "", ErrInvalid
	}
	id, err := randomID("mcs_")
	if err != nil {
		return "", err
	}
	now := time.Now().UnixMilli()
	query, err := json.Marshal(map[string]any{"title": strings.TrimSpace(search.Title), "year": search.Year, "page": search.Page})
	if err != nil {
		return "", err
	}
	err = store.WithTx(ctx, func(tx *sql.Tx) error {
		current, _, err := readProviderCredentialForUse(ctx, tx, actor, search.LibraryID, authority.ConnectionID)
		if err != nil {
			return err
		}
		if *current != authority {
			return ErrRevisionConflict
		}
		policy, err := readLibraryMetadataPolicy(ctx, tx, actor, search.LibraryID)
		if err != nil {
			return err
		}
		if !policy.OnlineEnabled() || policy.ConnectionID != authority.ConnectionID ||
			policy.Language != search.Language || policy.Region != search.Region {
			return ErrRevisionConflict
		}
		var kind string
		var itemRevision, structureRevision int64
		if err := tx.QueryRowContext(ctx, `SELECT i.kind,i.revision,st.structure_revision FROM library_item i
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id AND st.superseded_by IS NULL
WHERE i.library_id=? AND i.id=?`, search.LibraryID, search.ItemID).Scan(&kind, &itemRevision, &structureRevision); err != nil {
			return err
		}
		if itemRevision != search.ItemRevision || structureRevision != search.StructureRevision ||
			kind != search.Kind && kind != "video" {
			return ErrRevisionConflict
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO provider_match_candidate_set(id,actor_user_id,library_id,item_id,connection_id,kind,
query_json,language,region,policy_revision,item_revision,structure_revision,rules_version,created_at,expires_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, id, actor, search.LibraryID, search.ItemID, authority.ConnectionID, search.Kind,
			string(query), search.Language, search.Region, authority.PolicyRevision, search.ItemRevision, search.StructureRevision,
			search.RulesVersion, now, now+15*60*1000); err != nil {
			return err
		}
		for position, candidate := range candidates {
			if candidate.Provider != "tmdb" || candidate.Kind != search.Kind || candidate.ExternalID < 1 ||
				strings.TrimSpace(candidate.Title) == "" || len(candidate.Evidence) > 32 {
				return ErrInvalid
			}
			evidence, err := json.Marshal(candidate.Evidence)
			if err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO provider_match_candidate(set_id,provider,entity_kind,external_id,title,
original_title,year,evidence_json,position) VALUES(?,?,?,?,?,?,?,?,?)`, id, candidate.Provider, candidate.Kind,
				candidate.ExternalID, candidate.Title, candidate.OriginalTitle, candidate.Year, string(evidence), position); err != nil {
				return err
			}
		}
		return nil
	})
	return id, err
}

func (store *Store) ResolveMatchCandidate(ctx context.Context, actor uint, authority ProviderUseAuthority,
	request ProviderMatchRequest, candidateSetID string) (string, error) {
	id, _, err := resolveMatchCandidate(ctx, store.db, actor, authority, request, candidateSetID, time.Now().UnixMilli())
	return id, err
}

type matchCandidateReader interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func resolveMatchCandidate(ctx context.Context, reader matchCandidateReader, actor uint, authority ProviderUseAuthority,
	request ProviderMatchRequest, candidateSetID string, now int64) (string, []string, error) {
	query := `SELECT s.id,c.evidence_json FROM provider_match_candidate_set s
JOIN provider_match_candidate c ON c.set_id=s.id
JOIN library_item i ON i.library_id=s.library_id AND i.id=s.item_id
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id AND st.superseded_by IS NULL
WHERE s.actor_user_id=? AND s.library_id=? AND s.item_id=? AND s.connection_id=? AND s.kind=?
AND s.policy_revision=? AND s.item_revision=? AND s.structure_revision=? AND s.expires_at>?
AND i.revision=? AND st.structure_revision=?
AND c.provider='tmdb' AND c.entity_kind=? AND c.external_id=?`
	args := []any{actor, request.LibraryID, request.ItemID, authority.ConnectionID, request.Kind,
		authority.PolicyRevision, request.ItemRevision, request.StructureRevision, now,
		request.ItemRevision, request.StructureRevision, request.Kind, request.ExternalID}
	if candidateSetID != "" {
		query += ` AND s.id=?`
		args = append(args, candidateSetID)
	}
	query += ` ORDER BY s.created_at DESC,s.id LIMIT 1`
	var id, evidence string
	if err := reader.QueryRowContext(ctx, query, args...).Scan(&id, &evidence); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", nil, ErrNotFound
		}
		return "", nil, err
	}
	var values []string
	if err := json.Unmarshal([]byte(evidence), &values); err != nil {
		return "", nil, err
	}
	return id, values, nil
}
