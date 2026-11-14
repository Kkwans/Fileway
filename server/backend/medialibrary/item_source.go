package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
)

// ItemSourceAccess is server-internal source evidence, never a client DTO or
// authority to open a file. Callers must revalidate the source owner's current
// account, policy, binding and safe-open rules for every access.
type ItemSourceAccess struct {
	ID              string           `json:"-"`
	SourceFileID    string           `json:"-"`
	BindingID       string           `json:"-"`
	Source          LocalSource      `json:"-"`
	Binding         SourceBindingRef `json:"-"`
	Proof           *BindingProof    `json:"-"`
	Locator         []byte           `json:"-"`
	ObjectKey       []byte           `json:"-"`
	IdentityKind    string           `json:"-"`
	IdentityEpoch   int64            `json:"-"`
	LocatorRevision int64            `json:"-"`
	SourceVersion   string           `json:"-"`
	MissingSince    *int64           `json:"-"`
}

func (store *Store) GetItemMetadataSourceAccess(ctx context.Context, viewer uint, libraryID, itemID, inputID string) (*ItemMetadataSourceAccess, error) {
	if viewer == 0 {
		return nil, ErrForbidden
	}
	var entry ItemMetadataSourceAccess
	proof := &BindingProof{}
	err := store.db.QueryRowContext(ctx, `SELECT mi.id,ls.binding_id,s.id,s.owner_user_id,s.configuration_ref,s.authorization_revision,s.created_at,ls.root_locator,ls.revision,mi.source_key,mi.digest,mi.parser_version,ls.proof_epoch,ls.proof_version,ls.mapping_digest,ls.root_identity_kind,ls.root_identity,ls.attested_at
FROM metadata_input mi JOIN library_source ls ON ls.binding_id=mi.binding_id AND ls.library_id=mi.library_id JOIN source s ON s.id=ls.source_id JOIN library l ON l.id=mi.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE mi.library_id=? AND mi.item_id=? AND mi.id=? AND `+libraryVisibility+` AND l.archived_at IS NULL AND s.owner_user_id=l.owner_user_id AND s.kind='local' AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.active_proof_version=1
AND ((mi.is_current=1 AND mi.status='candidate') OR EXISTS(SELECT 1 FROM item_field f WHERE f.library_id=mi.library_id AND f.item_id=mi.item_id AND f.input_id=mi.id)
OR EXISTS(SELECT 1 FROM item_credit c WHERE c.library_id=mi.library_id AND c.item_id=mi.item_id AND c.input_id=mi.id AND c.active=1)
OR EXISTS(SELECT 1 FROM rating_observation r WHERE r.library_id=mi.library_id AND r.item_id=mi.item_id AND r.input_id=mi.id AND r.active=1)
OR EXISTS(SELECT 1 FROM artwork_declaration a WHERE a.library_id=mi.library_id AND a.item_id=mi.item_id AND a.input_id=mi.id AND a.active=1)
OR EXISTS(SELECT 1 FROM item_external_id x WHERE x.library_id=mi.library_id AND x.item_id=mi.item_id AND x.input_id=mi.id AND x.active=1))`, viewer, libraryID, itemID, inputID, viewer).Scan(&entry.ID, &entry.BindingID, &entry.Source.ID, &entry.Source.OwnerUserID, &entry.Source.ConfigurationRef, &entry.Source.AuthorizationRevision, &entry.Source.CreatedAt, &entry.Binding.RootLocator, &entry.Binding.BindingRevision, &entry.SourceKey, &entry.Digest, &entry.ParserVersion, &entry.Binding.ProofEpoch, &proof.Version, &proof.MappingDigest, &proof.RootIdentityKind, &proof.RootIdentity, &proof.AttestedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	entry.Binding.BindingID, entry.Binding.LibraryID, entry.Binding.SourceID, entry.Binding.AuthorizationRevision = entry.BindingID, libraryID, entry.Source.ID, entry.Source.AuthorizationRevision
	entry.Proof = proof
	return &entry, nil
}

type ItemSourceAccessPage struct {
	Items      []ItemSourceAccess
	NextCursor string
}

func (store *Store) ListItemSourceAccess(ctx context.Context, viewer uint, libraryID, itemID, afterID string, limit int) (*ItemSourceAccessPage, error) {
	if viewer == 0 {
		return nil, ErrForbidden
	}
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("%w: item source page limit", ErrInvalid)
	}
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, `SELECT ls.binding_id || ':' || lf.source_file_id AS cursor, lf.source_file_id, ls.binding_id,
s.id, s.owner_user_id, s.configuration_ref, s.authorization_revision, s.created_at, ls.root_locator, ls.revision, lf.locator, lf.missing_since,
f.object_key,f.identity_kind,f.identity_epoch,f.source_version,lf.locator_revision
 ,ls.proof_epoch,ls.proof_version,ls.mapping_digest,ls.root_identity_kind,ls.root_identity,ls.attested_at
FROM library_file lf JOIN library_source ls ON ls.library_id = lf.library_id AND ls.source_id = lf.source_id AND ls.root_locator = lf.root_locator
JOIN source s ON s.id = lf.source_id JOIN source_file f ON f.id = lf.source_file_id AND f.source_id = lf.source_id JOIN library l ON l.id = lf.library_id
LEFT JOIN library_member m ON m.library_id = l.id AND m.user_id = ? AND m.revoked_at IS NULL
WHERE lf.library_id = ? AND `+libraryVisibility+` AND l.archived_at IS NULL AND s.owner_user_id = l.owner_user_id AND s.kind = 'local' AND lf.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.active_proof_version=1
AND EXISTS (SELECT 1 FROM edition e JOIN variant v ON v.library_id = e.library_id AND v.edition_id = e.id
JOIN library_item mapped ON mapped.library_id=e.library_id AND mapped.id=e.item_id
JOIN file_item_mapping fm ON fm.library_id=v.library_id AND fm.variant_id=v.id AND fm.item_id=e.item_id AND fm.source_file_id=v.source_file_id AND fm.binding_id=ls.binding_id AND fm.binding_epoch=ls.proof_epoch AND fm.active=1 AND (mapped.kind!='episode' OR fm.confirmed_by='manual' OR fm.evidence_id IS NULL OR EXISTS(SELECT 1 FROM identification_evidence ie WHERE ie.id=fm.evidence_id AND ie.match_state!='needs_review'))
WHERE e.library_id = lf.library_id AND e.item_id = ? AND v.source_file_id = lf.source_file_id)
AND (ls.binding_id || ':' || lf.source_file_id) > ? ORDER BY cursor LIMIT ?`, viewer, libraryID, viewer, itemID, afterID, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &ItemSourceAccessPage{Items: make([]ItemSourceAccess, 0, limit)}
	for rows.Next() {
		var entry ItemSourceAccess
		proof := &BindingProof{}
		err := rows.Scan(&entry.ID, &entry.SourceFileID, &entry.BindingID, &entry.Source.ID, &entry.Source.OwnerUserID, &entry.Source.ConfigurationRef, &entry.Source.AuthorizationRevision, &entry.Source.CreatedAt, &entry.Binding.RootLocator, &entry.Binding.BindingRevision, &entry.Locator, &entry.MissingSince, &entry.ObjectKey, &entry.IdentityKind, &entry.IdentityEpoch, &entry.SourceVersion, &entry.LocatorRevision, &entry.Binding.ProofEpoch, &proof.Version, &proof.MappingDigest, &proof.RootIdentityKind, &proof.RootIdentity, &proof.AttestedAt)
		if err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].ID
			break
		}
		entry.Binding.BindingID, entry.Binding.LibraryID, entry.Binding.SourceID, entry.Binding.AuthorizationRevision = entry.BindingID, libraryID, entry.Source.ID, entry.Source.AuthorizationRevision
		entry.Proof = proof
		page.Items = append(page.Items, entry)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}

// ItemMetadataSourceAccess is private source provenance for authorization and
// freshness checks, not part of an ItemDetail or any client response.
type ItemMetadataSourceAccess struct {
	ID            string           `json:"-"`
	BindingID     string           `json:"-"`
	Source        LocalSource      `json:"-"`
	Binding       SourceBindingRef `json:"-"`
	Proof         *BindingProof    `json:"-"`
	SourceKey     []byte           `json:"-"`
	Digest        []byte           `json:"-"`
	ParserVersion int              `json:"-"`
}

type ItemMetadataSourceAccessPage struct {
	Items      []ItemMetadataSourceAccess
	NextCursor string
}

func (store *Store) ListItemMetadataSourceAccess(ctx context.Context, viewer uint, libraryID, itemID, afterID string, limit int) (*ItemMetadataSourceAccessPage, error) {
	if viewer == 0 {
		return nil, ErrForbidden
	}
	if limit == 0 {
		limit = 50
	}
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("%w: metadata source page limit", ErrInvalid)
	}
	if _, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID); err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, `SELECT mi.id,ls.binding_id,s.id,s.owner_user_id,s.configuration_ref,s.authorization_revision,s.created_at,ls.root_locator,ls.revision,mi.source_key,mi.digest,mi.parser_version,ls.proof_epoch,ls.proof_version,ls.mapping_digest,ls.root_identity_kind,ls.root_identity,ls.attested_at
FROM metadata_input mi JOIN library_source ls ON ls.binding_id=mi.binding_id AND ls.library_id=mi.library_id
JOIN source s ON s.id=ls.source_id JOIN library l ON l.id=mi.library_id
LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE mi.library_id=? AND mi.item_id=? AND `+libraryVisibility+` AND l.archived_at IS NULL AND s.owner_user_id=l.owner_user_id AND s.kind='local' AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.active_proof_version=1
AND ((mi.is_current=1 AND mi.status='candidate') OR EXISTS(SELECT 1 FROM item_field f WHERE f.library_id=mi.library_id AND f.item_id=mi.item_id AND f.input_id=mi.id)
OR EXISTS(SELECT 1 FROM item_credit c WHERE c.library_id=mi.library_id AND c.item_id=mi.item_id AND c.input_id=mi.id AND c.active=1)
OR EXISTS(SELECT 1 FROM rating_observation r WHERE r.library_id=mi.library_id AND r.item_id=mi.item_id AND r.input_id=mi.id AND r.active=1)
OR EXISTS(SELECT 1 FROM artwork_declaration a WHERE a.library_id=mi.library_id AND a.item_id=mi.item_id AND a.input_id=mi.id AND a.active=1)
OR EXISTS(SELECT 1 FROM item_external_id x WHERE x.library_id=mi.library_id AND x.item_id=mi.item_id AND x.input_id=mi.id AND x.active=1))
AND mi.id>? ORDER BY mi.id LIMIT ?`, viewer, libraryID, itemID, viewer, afterID, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &ItemMetadataSourceAccessPage{Items: make([]ItemMetadataSourceAccess, 0, limit)}
	for rows.Next() {
		var entry ItemMetadataSourceAccess
		proof := &BindingProof{}
		if err := rows.Scan(&entry.ID, &entry.BindingID, &entry.Source.ID, &entry.Source.OwnerUserID, &entry.Source.ConfigurationRef, &entry.Source.AuthorizationRevision, &entry.Source.CreatedAt, &entry.Binding.RootLocator, &entry.Binding.BindingRevision, &entry.SourceKey, &entry.Digest, &entry.ParserVersion, &entry.Binding.ProofEpoch, &proof.Version, &proof.MappingDigest, &proof.RootIdentityKind, &proof.RootIdentity, &proof.AttestedAt); err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].ID
			break
		}
		entry.Binding.BindingID, entry.Binding.LibraryID, entry.Binding.SourceID, entry.Binding.AuthorizationRevision = entry.BindingID, libraryID, entry.Source.ID, entry.Source.AuthorizationRevision
		entry.Proof = proof
		page.Items = append(page.Items, entry)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}
