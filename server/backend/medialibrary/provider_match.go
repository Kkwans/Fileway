package medialibrary

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strconv"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

type ProviderMatchRequest struct {
	LibraryID, ItemID, ConnectionID, Kind       string
	ExternalID, ItemRevision, StructureRevision int64
	Automatic                                   bool `json:",omitempty"`
	job                                         *MetadataJob
}

type ProviderMatchDocument struct {
	Item         mediametadata.Item `json:"item"`
	PosterPath   string             `json:"posterPath,omitempty"`
	BackdropPath string             `json:"backdropPath,omitempty"`
}

type ProviderMatchResult struct {
	LibraryID    string `json:"libraryId"`
	ItemID       string `json:"itemId"`
	Kind         string `json:"kind"`
	ExternalID   string `json:"externalId"`
	ItemRevision int64  `json:"itemRevision"`
}

type ProviderMatch struct {
	Kind           string
	ExternalID     int64
	FetchedAt      int64
	Revision       int64
	OnlineFirst    bool
	PolicyRevision int64
	Document       ProviderMatchDocument
}

const providerMatchOperation = "metadata.tmdb.confirm"

func providerMatchReceiptInput(request ProviderMatchRequest) (string, ProviderMatchRequest) {
	if request.Automatic {
		request.ExternalID = 0
		return "metadata.tmdb.fill", request
	}
	return providerMatchOperation, request
}

func (store *Store) ReplayProviderMatch(ctx context.Context, actor uint, operationID string, request ProviderMatchRequest) (*ProviderMatchResult, bool, error) {
	if _, err := editableCatalogLibrary(ctx, store.db, actor, request.LibraryID); err != nil {
		return nil, false, err
	}
	operationKind, input := providerMatchReceiptInput(request)
	digest, err := operationDigest(input)
	if err != nil {
		return nil, false, err
	}
	return lookupOperation[*ProviderMatchResult](ctx, store.db, actor, operationKind, operationID, digest)
}

// ConfirmProviderMatch saves fetched data and a replay receipt together. The
// HTTP boundary verifies live file access before calling it and before reply.
// Provider data is retained independently so changing priority is reversible.
func (store *Store) ConfirmProviderMatch(ctx context.Context, actor uint, operationID string, request ProviderMatchRequest,
	authority ProviderUseAuthority, language string, fetchedAt int64, document ProviderMatchDocument) (*ProviderMatchResult, error) {
	kind, ok := map[string]mediametadata.Kind{"movie": mediametadata.Movie, "series": mediametadata.TVShow}[request.Kind]
	if !ok || kind != document.Item.Kind || request.ExternalID <= 0 || request.ItemRevision < 1 ||
		request.StructureRevision < 1 || fetchedAt <= 0 || (request.ConnectionID != "" && request.ConnectionID != authority.ConnectionID) {
		return nil, ErrInvalid
	}
	fields := make(map[string][]string, len(document.Item.Fields))
	for name, values := range document.Item.Fields {
		if validateMetadataValues(name, values) == nil {
			fields[name] = values
		}
	}
	if len(fields["title"]) == 0 {
		return nil, ErrInvalid
	}
	document.Item.Fields = fields
	encoded, err := json.Marshal(document)
	if err != nil || len(encoded) > 256<<10 {
		return nil, ErrInvalid
	}
	operationKind, input := providerMatchReceiptInput(request)
	return providerOperation(ctx, store, actor, operationKind, operationID, input, func(tx *sql.Tx) error {
		current, _, err := readProviderCredentialForUse(ctx, tx, actor, request.LibraryID, authority.ConnectionID)
		if err != nil {
			return err
		}
		if *current != authority {
			return ErrRevisionConflict
		}
		return nil
	}, func(tx *sql.Tx) (*ProviderMatchResult, error) {
		var itemKind string
		var revision, structureRevision int64
		err := tx.QueryRowContext(ctx, `SELECT i.kind,i.revision,st.structure_revision FROM library_item i
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id
WHERE i.library_id=? AND i.id=? AND st.superseded_by IS NULL`, request.LibraryID, request.ItemID).Scan(&itemKind, &revision, &structureRevision)
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		if err != nil {
			return nil, err
		}
		if revision != request.ItemRevision || structureRevision != request.StructureRevision {
			return nil, ErrRevisionConflict
		}
		if request.Kind != itemKind && (itemKind != "video" || request.Kind != "movie") {
			return nil, ErrInvalid
		}
		if request.Automatic {
			var existing bool
			if err := tx.QueryRowContext(ctx, `SELECT EXISTS(
SELECT 1 FROM item_provider_match WHERE library_id=?1 AND item_id=?2
UNION ALL SELECT 1 FROM provider_match_manual_review WHERE library_id=?1 AND item_id=?2)`, request.LibraryID, request.ItemID).Scan(&existing); err != nil {
				return nil, err
			}
			if existing {
				return nil, ErrRevisionConflict
			}
		}
		if job := request.job; job != nil {
			scan, err := getScan(ctx, tx, actor, job.ScanID)
			if err != nil {
				return nil, err
			}
			if scan.Generation != job.ScanGeneration || scan.Status != "completed" {
				return nil, ErrRevisionConflict
			}
			if _, err := validateSourceBinding(ctx, tx, actor, scan.Binding); err != nil {
				return nil, err
			}
			if err := acknowledgeMetadataJobTarget(ctx, tx, job.ID, job.Generation, job.LibraryID, request.ItemID, "matched", ""); err != nil {
				return nil, err
			}
		}
		if err := recordProviderMatchRevision(ctx, tx, request.LibraryID, request.ItemID, revision+1, structureRevision); err != nil {
			return nil, err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO item_provider_match(library_id,item_id,connection_id,provider,entity_kind,external_id,language,document_json,fetched_at,revision)
VALUES(?,?,?,'tmdb',?,?,?,?,?,?) ON CONFLICT(library_id,item_id) DO UPDATE SET
connection_id=excluded.connection_id,entity_kind=excluded.entity_kind,external_id=excluded.external_id,language=excluded.language,
document_json=excluded.document_json,fetched_at=excluded.fetched_at,revision=excluded.revision`, request.LibraryID,
			request.ItemID, authority.ConnectionID, request.Kind, request.ExternalID, language, string(encoded), fetchedAt, revision+1)
		if err != nil {
			return nil, err
		}
		if !request.Automatic {
			if _, err := tx.ExecContext(ctx, `DELETE FROM provider_match_manual_review WHERE library_id=? AND item_id=?`, request.LibraryID, request.ItemID); err != nil {
				return nil, err
			}
		}
		if _, err = tx.ExecContext(ctx, `UPDATE library_item SET revision=revision+1 WHERE library_id=? AND id=?`, request.LibraryID, request.ItemID); err != nil {
			return nil, err
		}
		if err = publishCatalogChange(ctx, tx, actor, request.LibraryID, "catalog.item.metadata.changed", catalogChange{ItemID: request.ItemID, Revision: revision + 1}); err != nil {
			return nil, err
		}
		return &ProviderMatchResult{request.LibraryID, request.ItemID, request.Kind, strconv.FormatInt(request.ExternalID, 10), revision + 1}, nil
	})
}

// VisibleProviderMatch reads only the catalog snapshot for a current library
// member. The caller must already have verified the item's live source access.
func (store *Store) VisibleProviderMatch(ctx context.Context, viewer uint, libraryID, itemID string, itemRevision int64) (*ProviderMatch, error) {
	if viewer == 0 {
		return nil, ErrForbidden
	}
	var value ProviderMatch
	var encoded, mode string
	err := store.db.QueryRowContext(ctx, `SELECT pm.entity_kind,pm.external_id,pm.document_json,pm.fetched_at,pm.revision,p.mode,p.revision
FROM item_provider_match pm JOIN library l ON l.id=pm.library_id AND l.archived_at IS NULL
JOIN library_item i ON i.library_id=pm.library_id AND i.id=pm.item_id
JOIN item_structure st ON st.library_id=i.library_id AND st.item_id=i.id AND st.superseded_by IS NULL
JOIN library_metadata_policy p ON p.library_id=l.id AND p.mode IN ('local_first_online','online_first')
LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE pm.library_id=? AND pm.item_id=? AND i.revision=? AND (pm.entity_kind=i.kind OR (i.kind='video' AND pm.entity_kind='movie')) AND `+libraryVisibility,
		viewer, libraryID, itemID, itemRevision, viewer).Scan(&value.Kind, &value.ExternalID, &encoded, &value.FetchedAt, &value.Revision, &mode, &value.PolicyRevision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	value.OnlineFirst = mode == MetadataOnlineFirst
	if err := json.Unmarshal([]byte(encoded), &value.Document); err != nil {
		return nil, err
	}
	return &value, nil
}
