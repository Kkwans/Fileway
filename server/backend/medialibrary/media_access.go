package medialibrary

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
)

func sameAccessProof(left, right *BindingProof) bool {
	if left == nil || right == nil {
		return left == nil && right == nil
	}
	return left.Version == right.Version && left.RootIdentityKind == right.RootIdentityKind && left.AttestedAt == right.AttestedAt && bytes.Equal(left.MappingDigest, right.MappingDigest) && bytes.Equal(left.RootIdentity, right.RootIdentity)
}

func sameSourceAuthority(leftSource, rightSource LocalSource, leftBinding, rightBinding SourceBindingRef, leftProof, rightProof *BindingProof) bool {
	return leftSource.ID == rightSource.ID && leftSource.OwnerUserID == rightSource.OwnerUserID && leftSource.ConfigurationRef == rightSource.ConfigurationRef && leftSource.AuthorizationRevision == rightSource.AuthorizationRevision && sameBinding(leftBinding, rightBinding) && sameAccessProof(leftProof, rightProof)
}

// SamePlaybackAccess compares every catalog component that anchors a media
// lease. It does not replace live account, policy or safe-open validation.
func SamePlaybackAccess(left, right *PlaybackAccess) bool {
	if left == nil || right == nil {
		return false
	}
	if left.LibraryID != right.LibraryID || left.ItemID != right.ItemID || left.EditionID != right.EditionID || left.VariantID != right.VariantID || left.ViewerUserID != right.ViewerUserID || left.MembershipRole != right.MembershipRole || left.MembershipRevision != right.MembershipRevision || left.LibraryAccessRevision != right.LibraryAccessRevision || left.MappingRevision != right.MappingRevision || left.EditionMappingRevision != right.EditionMappingRevision || left.FileRevision != right.FileRevision || left.SourceFileID != right.SourceFileID || left.BindingID != right.BindingID || left.LocatorRevision != right.LocatorRevision || left.IdentityKind != right.IdentityKind || left.IdentityEpoch != right.IdentityEpoch || left.SourceVersion != right.SourceVersion || !bytes.Equal(left.ObjectKey, right.ObjectKey) || !bytes.Equal(left.Locator, right.Locator) {
		return false
	}
	if (left.MissingSince == nil) != (right.MissingSince == nil) {
		return false
	}
	if left.MissingSince != nil && *left.MissingSince != *right.MissingSince {
		return false
	}
	return sameSourceAuthority(left.Source, right.Source, left.Binding, right.Binding, left.Proof, right.Proof)
}

// SameArtworkAccess fences declaration and source-provenance ABA separately
// from metadata edits. Actual NFO/image bytes still need safe-open checks.
func SameArtworkAccess(left, right *ArtworkAccess) bool {
	if left == nil || right == nil {
		return false
	}
	if left.LibraryID != right.LibraryID || left.ItemID != right.ItemID || left.ArtworkID != right.ArtworkID || left.Role != right.Role || left.Status != right.Status || left.ViewerUserID != right.ViewerUserID || left.MembershipRole != right.MembershipRole || left.MembershipRevision != right.MembershipRevision || left.LibraryAccessRevision != right.LibraryAccessRevision || left.DeclarationRevision != right.DeclarationRevision || left.ID != right.ID || left.BindingID != right.BindingID || left.ParserVersion != right.ParserVersion || !bytes.Equal(left.Reference, right.Reference) || !bytes.Equal(left.SourceKey, right.SourceKey) || !bytes.Equal(left.Digest, right.Digest) {
		return false
	}
	return sameSourceAuthority(left.Source, right.Source, left.Binding, right.Binding, left.Proof, right.Proof)
}

// PlaybackAccess is private catalog evidence, never a read authorization or a
// client response. The caller must resolve the live viewer, source owner,
// administrator policy and safely opened file before delivering media bytes.
type PlaybackAccess struct {
	ItemSourceAccess
	LibraryID              string     `json:"-"`
	ItemID                 string     `json:"-"`
	EditionID              string     `json:"-"`
	VariantID              string     `json:"-"`
	ViewerUserID           uint       `json:"-"`
	MembershipRole         MemberRole `json:"-"`
	MembershipRevision     int64      `json:"-"` // owner has sentinel 0
	LibraryAccessRevision  int64      `json:"-"` // archive/restore and owner ABA fence
	MappingRevision        int64      `json:"-"` // variant -> edition/file
	EditionMappingRevision int64      `json:"-"` // edition -> item
	FileRevision           int64      `json:"-"`
}

type PlaybackAccessPage struct {
	Items      []PlaybackAccess `json:"-"`
	NextCursor string           `json:"-"`
}

// PlaybackVariant is an internal candidate. Source owner authority must be
// checked for at least one binding before a candidate is shown as playable.
type PlaybackVariant struct {
	EditionID              string `json:"editionId"`
	VariantID              string `json:"variantId"`
	EditionName            string `json:"editionName"`
	IsDefault              bool   `json:"isDefault"`
	MappingRevision        int64  `json:"-"`
	EditionMappingRevision int64  `json:"-"`
	Cursor                 string `json:"-"`
}

type PlaybackVariantPage struct {
	Items           []PlaybackVariant
	NextCursor      string
	ItemRevision    int64
	LibraryRevision int64
}

const playbackAccessSelection = `SELECT ls.binding_id || ':' || f.id, f.id, ls.binding_id,
s.id,s.owner_user_id,s.configuration_ref,s.authorization_revision,s.created_at,
ls.root_locator,ls.revision,lf.locator,lf.missing_since,f.object_key,f.identity_kind,f.identity_epoch,f.source_version,lf.locator_revision,
ls.proof_epoch,ls.proof_version,ls.mapping_digest,ls.root_identity_kind,ls.root_identity,ls.attested_at,
e.id,v.id,CASE WHEN l.owner_user_id=? THEN 'owner' ELSE m.role END,CASE WHEN l.owner_user_id=? THEN 0 ELSE m.revision END,
v.mapping_revision,e.mapping_revision,f.revision,l.access_revision
FROM library l JOIN library_item i ON i.library_id=l.id
JOIN edition e ON e.library_id=i.library_id AND e.item_id=i.id
JOIN variant v ON v.library_id=e.library_id AND v.edition_id=e.id
JOIN source_file f ON f.id=v.source_file_id
JOIN library_file lf ON lf.library_id=v.library_id AND lf.source_file_id=f.id AND lf.source_id=f.source_id
JOIN library_source ls ON ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator
JOIN file_item_mapping fm ON fm.library_id=v.library_id AND fm.variant_id=v.id AND fm.item_id=e.item_id AND fm.source_file_id=v.source_file_id AND fm.binding_id=ls.binding_id AND fm.binding_epoch=ls.proof_epoch AND fm.active=1 AND (i.kind!='episode' OR fm.confirmed_by='manual' OR fm.evidence_id IS NULL OR EXISTS(SELECT 1 FROM identification_evidence ie WHERE ie.id=fm.evidence_id AND ie.match_state!='needs_review'))
JOIN source s ON s.id=lf.source_id
LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE l.id=? AND i.id=? AND e.id=? AND v.id=? AND ` + libraryVisibility + ` AND l.archived_at IS NULL
AND i.kind IN ('movie','episode','video') AND s.owner_user_id=l.owner_user_id AND s.kind='local'
AND lf.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.proof_version=1`

func scanPlaybackAccess(row rowScanner, viewer uint, libraryID, itemID string) (*PlaybackAccess, error) {
	access := &PlaybackAccess{LibraryID: libraryID, ItemID: itemID, ViewerUserID: viewer}
	proof := &BindingProof{}
	err := row.Scan(&access.ID, &access.SourceFileID, &access.BindingID, &access.Source.ID, &access.Source.OwnerUserID, &access.Source.ConfigurationRef, &access.Source.AuthorizationRevision, &access.Source.CreatedAt,
		&access.Binding.RootLocator, &access.Binding.BindingRevision, &access.Locator, &access.MissingSince, &access.ObjectKey, &access.IdentityKind, &access.IdentityEpoch, &access.SourceVersion, &access.LocatorRevision,
		&access.Binding.ProofEpoch, &proof.Version, &proof.MappingDigest, &proof.RootIdentityKind, &proof.RootIdentity, &proof.AttestedAt,
		&access.EditionID, &access.VariantID, &access.MembershipRole, &access.MembershipRevision, &access.MappingRevision, &access.EditionMappingRevision, &access.FileRevision, &access.LibraryAccessRevision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := validateBindingProof(*proof); err != nil {
		return nil, err
	}
	access.Binding.BindingID, access.Binding.LibraryID, access.Binding.SourceID, access.Binding.AuthorizationRevision = access.BindingID, libraryID, access.Source.ID, access.Source.AuthorizationRevision
	access.Proof = proof
	return access, nil
}

// ResolvePlayback returns the exact viewer/library/edition/variant/binding
// relationship from one SQL statement. A successful lookup cannot authorize
// bytes and must be repeated when validating a lease.
func (store *Store) ResolvePlayback(ctx context.Context, viewer uint, libraryID, itemID, editionID, variantID, bindingID string) (*PlaybackAccess, error) {
	return resolvePlayback(ctx, store.db, viewer, libraryID, itemID, editionID, variantID, bindingID)
}

func resolvePlayback(ctx context.Context, reader schemaReader, viewer uint, libraryID, itemID, editionID, variantID, bindingID string) (*PlaybackAccess, error) {
	if viewer == 0 {
		return nil, ErrForbidden
	}
	if libraryID == "" || itemID == "" || editionID == "" || variantID == "" || bindingID == "" {
		return nil, fmt.Errorf("%w: playback reference", ErrInvalid)
	}
	return scanPlaybackAccess(reader.QueryRowContext(ctx, playbackAccessSelection+` AND ls.binding_id=?`, viewer, viewer, viewer, libraryID, itemID, editionID, variantID, viewer, bindingID), viewer, libraryID, itemID)
}

func (store *Store) ListPlaybackAccess(ctx context.Context, viewer uint, libraryID, itemID, editionID, variantID, afterBindingID string, limit int) (*PlaybackAccessPage, error) {
	if viewer == 0 {
		return nil, ErrForbidden
	}
	if libraryID == "" || itemID == "" || editionID == "" || variantID == "" || len(afterBindingID) > 256 {
		return nil, fmt.Errorf("%w: playback reference", ErrInvalid)
	}
	if limit == 0 {
		limit = 20
	}
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("%w: playback page limit", ErrInvalid)
	}
	rows, err := store.db.QueryContext(ctx, playbackAccessSelection+` AND ls.binding_id>? ORDER BY ls.binding_id LIMIT ?`, viewer, viewer, viewer, libraryID, itemID, editionID, variantID, viewer, afterBindingID, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &PlaybackAccessPage{Items: make([]PlaybackAccess, 0, limit)}
	for rows.Next() {
		access, err := scanPlaybackAccess(rows, viewer, libraryID, itemID)
		if err != nil {
			return nil, err
		}
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].BindingID
			break
		}
		page.Items = append(page.Items, *access)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return page, nil
}

func playbackVariantCursor(editionID, variantID string) string {
	value, _ := json.Marshal([2]string{editionID, variantID})
	return base64.RawURLEncoding.EncodeToString(value)
}
func parsePlaybackVariantCursor(cursor string) (string, string, error) {
	if cursor == "" {
		return "", "", nil
	}
	if len(cursor) > 512 {
		return "", "", ErrInvalid
	}
	data, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return "", "", ErrInvalid
	}
	var parts []string
	if err := json.Unmarshal(data, &parts); err != nil || len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return "", "", ErrInvalid
	}
	return parts[0], parts[1], nil
}

// ListPlaybackVariants is an internal, bounded candidate list. It does not
// prove that any binding is currently readable by its source owner.
func (store *Store) ListPlaybackVariants(ctx context.Context, viewer uint, libraryID, itemID, after string, limit int) (*PlaybackVariantPage, error) {
	if viewer == 0 {
		return nil, ErrForbidden
	}
	if limit == 0 {
		limit = 20
	}
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("%w: variant page limit", ErrInvalid)
	}
	afterEdition, afterVariant, err := parsePlaybackVariantCursor(after)
	if err != nil {
		return nil, err
	}
	library, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID)
	if err != nil {
		return nil, err
	}
	itemRevision, err := metadataPageRevision(ctx, store.db, viewer, libraryID, itemID)
	if err != nil {
		return nil, err
	}
	rows, err := store.db.QueryContext(ctx, `SELECT e.id,v.id,e.name,e.is_default,v.mapping_revision,e.mapping_revision
FROM edition e JOIN variant v ON v.edition_id=e.id AND v.library_id=e.library_id JOIN library_item i ON i.id=e.item_id AND i.library_id=e.library_id
JOIN library l ON l.id=e.library_id LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE e.library_id=? AND e.item_id=? AND `+libraryVisibility+` AND l.archived_at IS NULL AND i.kind IN ('movie','episode','video')
AND EXISTS(SELECT 1 FROM library_file lf JOIN library_source ls ON ls.library_id=lf.library_id AND ls.source_id=lf.source_id AND ls.root_locator=lf.root_locator JOIN source s ON s.id=lf.source_id
JOIN file_item_mapping fm ON fm.library_id=v.library_id AND fm.variant_id=v.id AND fm.item_id=e.item_id AND fm.source_file_id=v.source_file_id AND fm.binding_id=ls.binding_id AND fm.binding_epoch=ls.proof_epoch AND fm.active=1 AND (i.kind!='episode' OR fm.confirmed_by='manual' OR fm.evidence_id IS NULL OR EXISTS(SELECT 1 FROM identification_evidence ie WHERE ie.id=fm.evidence_id AND ie.match_state!='needs_review'))
WHERE lf.library_id=v.library_id AND lf.source_file_id=v.source_file_id AND lf.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.proof_version=1 AND s.owner_user_id=l.owner_user_id AND s.kind='local')
AND (e.id>? OR (e.id=? AND v.id>?)) ORDER BY e.id,v.id LIMIT ?`, viewer, libraryID, itemID, viewer, afterEdition, afterEdition, afterVariant, limit+1)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	page := &PlaybackVariantPage{Items: make([]PlaybackVariant, 0, limit), ItemRevision: itemRevision, LibraryRevision: library.Revision}
	for rows.Next() {
		var entry PlaybackVariant
		var isDefault int
		if err := rows.Scan(&entry.EditionID, &entry.VariantID, &entry.EditionName, &isDefault, &entry.MappingRevision, &entry.EditionMappingRevision); err != nil {
			return nil, err
		}
		entry.IsDefault = isDefault == 1
		entry.Cursor = playbackVariantCursor(entry.EditionID, entry.VariantID)
		if len(page.Items) == limit {
			page.NextCursor = page.Items[len(page.Items)-1].Cursor
			break
		}
		page.Items = append(page.Items, entry)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	current, err := metadataPageRevision(ctx, store.db, viewer, libraryID, itemID)
	if err != nil {
		return nil, err
	}
	liveLibrary, err := activeCatalogLibrary(ctx, store.db, viewer, libraryID)
	if err != nil {
		return nil, err
	}
	if current != itemRevision || liveLibrary.Revision != library.Revision {
		return nil, ErrRevisionConflict
	}
	return page, nil
}

// ArtworkAccess contains a private declaration and NFO provenance, not a URL.
// Resolve the live owner, NFO source and image path through safe opens before
// reading; the viewer's file permissions must never be elevated or replaced.
type ArtworkAccess struct {
	ItemMetadataSourceAccess
	LibraryID             string     `json:"-"`
	ItemID                string     `json:"-"`
	ArtworkID             string     `json:"-"`
	Role                  string     `json:"-"`
	Status                string     `json:"-"`
	Reference             []byte     `json:"-"`
	ViewerUserID          uint       `json:"-"`
	MembershipRole        MemberRole `json:"-"`
	MembershipRevision    int64      `json:"-"`
	LibraryAccessRevision int64      `json:"-"`
	DeclarationRevision   int64      `json:"-"`
}

func (store *Store) ResolveArtwork(ctx context.Context, viewer uint, libraryID, itemID, artworkID string) (*ArtworkAccess, error) {
	if viewer == 0 {
		return nil, ErrForbidden
	}
	if libraryID == "" || itemID == "" || artworkID == "" {
		return nil, fmt.Errorf("%w: artwork reference", ErrInvalid)
	}
	access := &ArtworkAccess{LibraryID: libraryID, ItemID: itemID, ArtworkID: artworkID, ViewerUserID: viewer}
	proof := &BindingProof{}
	err := store.db.QueryRowContext(ctx, `SELECT mi.id,ls.binding_id,s.id,s.owner_user_id,s.configuration_ref,s.authorization_revision,s.created_at,
ls.root_locator,ls.revision,mi.source_key,mi.digest,mi.parser_version,ls.proof_epoch,ls.proof_version,ls.mapping_digest,ls.root_identity_kind,ls.root_identity,ls.attested_at,
a.role,a.status,a.private_ref,CASE WHEN l.owner_user_id=? THEN 'owner' ELSE m.role END,CASE WHEN l.owner_user_id=? THEN 0 ELSE m.revision END,a.declaration_revision,l.access_revision
FROM artwork_declaration a JOIN metadata_input mi ON mi.id=a.input_id AND mi.library_id=a.library_id AND mi.item_id=a.item_id AND mi.binding_id=a.binding_id
JOIN library_source ls ON ls.binding_id=a.binding_id AND ls.library_id=a.library_id JOIN source s ON s.id=ls.source_id JOIN library l ON l.id=a.library_id
LEFT JOIN library_member m ON m.library_id=l.id AND m.user_id=? AND m.revoked_at IS NULL
WHERE a.library_id=? AND a.item_id=? AND a.id=? AND `+libraryVisibility+` AND l.archived_at IS NULL AND s.owner_user_id=l.owner_user_id AND s.kind='local'
AND a.active=1 AND a.status='declared' AND a.private_ref IS NOT NULL AND a.source_key=mi.source_key AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 AND ls.proof_version=1
AND EXISTS(SELECT 1 FROM edition e JOIN variant v ON v.library_id=e.library_id AND v.edition_id=e.id JOIN library_item mapped ON mapped.library_id=e.library_id AND mapped.id=e.item_id JOIN library_file lf ON lf.library_id=v.library_id AND lf.source_file_id=v.source_file_id JOIN library_source fs ON fs.library_id=lf.library_id AND fs.source_id=lf.source_id AND fs.root_locator=lf.root_locator JOIN source vs ON vs.id=fs.source_id AND vs.owner_user_id=l.owner_user_id AND vs.kind='local' JOIN file_item_mapping fm ON fm.library_id=v.library_id AND fm.variant_id=v.id AND fm.item_id=e.item_id AND fm.source_file_id=v.source_file_id AND fm.binding_id=fs.binding_id AND fm.binding_epoch=fs.proof_epoch AND fm.active=1 AND (mapped.kind!='episode' OR fm.confirmed_by='manual' OR fm.evidence_id IS NULL OR EXISTS(SELECT 1 FROM identification_evidence ie WHERE ie.id=fm.evidence_id AND ie.match_state!='needs_review')) WHERE e.library_id=a.library_id AND e.item_id=a.item_id AND lf.binding_epoch=fs.proof_epoch AND fs.proof_epoch>0 AND fs.proof_version=1)`, viewer, viewer, viewer, libraryID, itemID, artworkID, viewer).Scan(&access.ID, &access.BindingID, &access.Source.ID, &access.Source.OwnerUserID, &access.Source.ConfigurationRef, &access.Source.AuthorizationRevision, &access.Source.CreatedAt,
		&access.Binding.RootLocator, &access.Binding.BindingRevision, &access.SourceKey, &access.Digest, &access.ParserVersion, &access.Binding.ProofEpoch, &proof.Version, &proof.MappingDigest, &proof.RootIdentityKind, &proof.RootIdentity, &proof.AttestedAt,
		&access.Role, &access.Status, &access.Reference, &access.MembershipRole, &access.MembershipRevision, &access.DeclarationRevision, &access.LibraryAccessRevision)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := validateBindingProof(*proof); err != nil {
		return nil, err
	}
	access.Binding.BindingID, access.Binding.LibraryID, access.Binding.SourceID, access.Binding.AuthorizationRevision = access.BindingID, libraryID, access.Source.ID, access.Source.AuthorizationRevision
	access.Proof = proof
	return access, nil
}
