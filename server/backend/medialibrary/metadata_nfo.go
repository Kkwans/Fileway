package medialibrary

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"

	"github.com/Kkwans/nas-file-browser/backend/mediametadata"
)

var errNFOEvidenceTooLarge = errors.New("NFO evidence exceeds storage limit")

// sanitizeNFOEvidence retains private local artwork declarations, while raw
// remote references and arbitrary attributes are never persisted: they may
// contain credentials or query tokens and are not executable asset URLs.
func sanitizeNFOEvidence(document mediametadata.Document) (mediametadata.Document, error) {
	encoded, err := json.Marshal(document)
	if err != nil {
		return mediametadata.Document{}, err
	}
	if len(encoded) > 4<<20 {
		return mediametadata.Document{}, errNFOEvidenceTooLarge
	}
	var safe mediametadata.Document
	if err := json.Unmarshal(encoded, &safe); err != nil {
		return safe, err
	}
	cleanArt := func(art []mediametadata.ArtworkDeclaration) {
		for i := range art {
			status, _ := classifyArtworkReference(art[i].Reference)
			if status != "declared" {
				art[i].Reference = ""
			}
			art[i].Attributes = safeArtworkAttributes(art[i].Attributes)
			art[i].ContainerAttributes = safeArtworkAttributes(art[i].ContainerAttributes)
		}
	}
	cleanIDs := func(ids []mediametadata.ExternalID) {
		for i := range ids {
			if strings.Contains(ids[i].Value, "://") || strings.ContainsAny(ids[i].Value, "/?#@") {
				ids[i].Value = ""
			}
		}
	}
	for i := range safe.Items {
		item := &safe.Items[i]
		cleanArt(item.Artwork)
		cleanIDs(item.ExternalIDs)
		for j := range item.Ratings {
			item.Ratings[j] = sanitizeRating(item.Ratings[j])
		}
		for j := range item.Credits {
			cleanArt(item.Credits[j].Artwork)
			cleanIDs(item.Credits[j].ExternalIDs)
			item.Credits[j].Attributes = nil
		}
		for j := range item.Collections {
			cleanArt(item.Collections[j].Artwork)
			cleanIDs(item.Collections[j].ExternalIDs)
			item.Collections[j].Attributes = nil
		}
	}
	return safe, nil
}

func safeArtworkAttributes(attributes map[string]string) map[string]string {
	var result map[string]string
	for key, value := range attributes {
		switch strings.ToLower(key) {
		case "aspect", "type", "lang", "season":
			if len(value) <= 128 && !strings.ContainsAny(value, "/?#@") && !strings.Contains(value, "://") {
				if result == nil {
					result = map[string]string{}
				}
				result[key] = value
			}
		}
	}
	return result
}

func classifyArtworkReference(reference string) (string, []byte) {
	ref := strings.TrimSpace(reference)
	if ref == "" || len(ref) > 8192 || strings.ContainsRune(ref, '\x00') {
		return "invalid", nil
	}
	if strings.Contains(ref, "://") || strings.HasPrefix(ref, "//") || strings.HasPrefix(ref, `\\`) || strings.ContainsAny(ref, "?#") {
		return "remote_unavailable", nil
	}
	return "declared", []byte(ref)
}

func sanitizeRating(rating mediametadata.Rating) mediametadata.Rating {
	if strings.ContainsAny(rating.RawSource, "/?#@") || strings.Contains(rating.RawSource, "://") {
		rating.RawSource = ""
	}
	if rating.RawScale != nil && (strings.Contains(*rating.RawScale, "://") || strings.ContainsAny(*rating.RawScale, "?#@")) {
		rating.RawScale = nil
	}
	fields := make(map[string][]string, len(rating.Fields))
	for key, values := range rating.Fields {
		copied := append([]string(nil), values...)
		for i, value := range copied {
			if strings.Contains(value, "://") || strings.ContainsAny(value, "?#") {
				copied[i] = ""
			}
		}
		fields[key] = copied
	}
	rating.Fields = fields
	return rating
}

func safeExternalID(id mediametadata.ExternalID) (string, string, bool) {
	provider := strings.ToLower(strings.TrimSpace(id.Provider))
	value := strings.TrimSpace(id.Value)
	if provider == "" || value == "" || len(provider) > 64 || len(value) > 256 {
		return "", "", false
	}
	for _, r := range provider {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || r == '_' || r == '-' || r == '.' {
			continue
		}
		return "", "", false
	}
	for _, r := range value {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || r == '_' || r == '-' || r == '.' || r == ':' {
			continue
		}
		return "", "", false
	}
	return provider, value, true
}

func explicitIDConflict(ids []mediametadata.ExternalID) bool {
	seen := map[string]string{}
	for _, id := range ids {
		provider, value, ok := safeExternalID(id)
		if !ok {
			continue
		}
		if prior, exists := seen[provider]; exists && prior != value {
			return true
		}
		seen[provider] = value
	}
	return false
}

func nfoIssue(ctx context.Context, tx *sql.Tx, libraryID, itemID string, obs MetadataObservation) (string, error) {
	if obs.AssociationAmbiguous {
		return "ambiguous_association", nil
	}
	if len(obs.Document.Items) != 1 {
		return "multiple_items", nil
	}
	item := obs.Document.Items[0]
	if item.Kind != mediametadata.Movie && item.Kind != mediametadata.TVShow && item.Kind != mediametadata.Season && item.Kind != mediametadata.Episode {
		return "unsupported_kind", nil
	}
	if item.Kind == mediametadata.TVShow || item.Kind == mediametadata.Season {
		return "context_required", nil
	}
	if explicitIDConflict(item.ExternalIDs) {
		return "external_id_conflict", nil
	}
	for _, credit := range item.Credits {
		if explicitIDConflict(credit.ExternalIDs) {
			return "person_id_conflict", nil
		}
		names := nonemptyValues(credit.Fields["name"])
		if len(names) > 1 {
			return "invalid_credit", nil
		}
		if len(names) == 1 && validateMetadataValues("title", names) != nil {
			return "invalid_credit", nil
		}
		roles := nonemptyValues(credit.Fields["role"])
		if len(roles) > 128 {
			return "invalid_credit", nil
		}
		for _, role := range roles {
			if validateMetadataValues("title", []string{role}) != nil {
				return "invalid_credit", nil
			}
		}
		_, conflict, err := findCreditPerson(ctx, tx, libraryID, credit.ExternalIDs)
		if err != nil {
			return "", err
		}
		if conflict {
			return "person_id_conflict", nil
		}
	}
	if conflict, err := creditIdentityConflict(ctx, tx, libraryID, item.Credits); err != nil {
		return "", err
	} else if conflict {
		return "person_id_conflict", nil
	}
	for name, values := range item.Fields {
		if metadataFieldLimit(name) == 0 {
			continue
		}
		filtered := nonemptyValues(values)
		if len(filtered) == 0 {
			continue
		}
		if err := validateMetadataValues(name, filtered); err != nil {
			return "invalid_metadata_field", nil
		}
	}
	before, err := readMetadataSnapshot(ctx, tx, libraryID, itemID)
	if err != nil {
		return "", err
	}
	projected := map[string][]string{}
	states := map[string]metadataFieldState{}
	for _, field := range before.Fields {
		visible, err := fieldHasCurrentOrigin(ctx, tx, libraryID, field)
		if err != nil {
			return "", err
		}
		if visible {
			projected[field.Name] = field.Values
		}
		states[field.Name] = field
	}
	for name, values := range item.Fields {
		if metadataFieldLimit(name) == 0 {
			continue
		}
		values = nonemptyValues(values)
		if len(values) == 0 {
			continue
		}
		if old, ok := states[name]; ok && (old.Locked || old.ExplicitClear) {
			continue
		}
		projected[name] = values
	}
	total := 0
	for _, values := range projected {
		raw, err := json.Marshal(values)
		if err != nil {
			return "", err
		}
		total += len(raw)
	}
	if total > 64<<10 {
		return "metadata_too_large", nil
	}
	for _, id := range item.ExternalIDs {
		provider, value, ok := safeExternalID(id)
		if !ok {
			continue
		}
		var existing string
		err := tx.QueryRowContext(ctx, `SELECT x.external_id FROM item_external_id x JOIN metadata_input mi ON mi.id=x.input_id JOIN library_source ls ON ls.binding_id=mi.binding_id WHERE x.library_id=? AND x.item_id=? AND x.provider=? AND x.entity_kind=? AND x.active=1 AND x.external_id!=? AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0 LIMIT 1`, libraryID, itemID, provider, string(item.Kind), value).Scan(&existing)
		if err == nil {
			return "external_id_conflict", nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return "", err
		}
	}
	// Multiple independent NFOs for an already populated item must be reviewed
	// instead of allowing traversal order to choose the winning source.
	var other int
	err = tx.QueryRowContext(ctx, `SELECT count(*) FROM item_field f JOIN metadata_input mi ON mi.id=f.input_id JOIN library_source ls ON ls.binding_id=mi.binding_id WHERE f.library_id=? AND f.item_id=? AND f.source='nfo' AND f.source_key!=? AND mi.binding_epoch=ls.proof_epoch AND ls.proof_epoch>0`, libraryID, itemID, obs.SourceKey).Scan(&other)
	if err != nil {
		return "", err
	}
	if other > 0 {
		return "multiple_nfo_sources", nil
	}
	return "", nil
}

func nonemptyValues(values []string) []string {
	result := make([]string, 0, len(values))
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			result = append(result, value)
		}
	}
	return result
}

// applyNFOObservationTx is called only inside the same scan transaction as
// the file, initial item/link, checkpoint and outbox. The scanner supplies
// evidence after safe-open and live-owner checks; this method never opens it.
func applyNFOObservationTx(ctx context.Context, tx *sql.Tx, actor uint, binding SourceBindingRef, itemID string, obs MetadataObservation) error {
	if len(obs.SourceKey) == 0 || len(obs.SourceKey) > 8192 || len(obs.Digest) != sha256.Size || obs.ParserVersion < 1 {
		return fmt.Errorf("%w: NFO observation", ErrInvalid)
	}
	stored, err := validateSourceBinding(ctx, tx, actor, binding)
	if err != nil {
		return err
	}
	safe, err := sanitizeNFOEvidence(obs.Document)
	tooLarge := errors.Is(err, errNFOEvidenceTooLarge)
	if err != nil && !tooLarge {
		return err
	}
	if tooLarge {
		// Keep bounded type evidence and the original digest; the source file
		// can be reviewed later without treating oversized text as a scan error.
		safe = mediametadata.Document{}
		for _, item := range obs.Document.Items {
			if len(safe.Items) >= mediametadata.DefaultMaxElements {
				break
			}
			switch item.Kind {
			case mediametadata.Movie, mediametadata.TVShow, mediametadata.Season, mediametadata.Episode:
				safe.Items = append(safe.Items, mediametadata.Item{Kind: item.Kind})
			}
		}
	}
	evidence, err := json.Marshal(safe)
	if err != nil {
		return err
	}
	var inputID string
	var oldDigest []byte
	var oldParser int
	var stale, oldAmbiguous int
	var oldStatus string
	err = tx.QueryRowContext(ctx, `SELECT id,digest,parser_version,projection_stale,status,association_ambiguous FROM metadata_input WHERE library_id=? AND item_id=? AND binding_id=? AND binding_epoch=? AND source_type='nfo' AND source_key=? AND is_current=1`, binding.LibraryID, itemID, stored.ID, stored.ProofEpoch, obs.SourceKey).Scan(&inputID, &oldDigest, &oldParser, &stale, &oldStatus, &oldAmbiguous)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	same := err == nil && bytes.Equal(oldDigest, obs.Digest) && oldParser == obs.ParserVersion && (oldAmbiguous == 1) == obs.AssociationAmbiguous
	if same && stale == 0 {
		return nil
	}
	issue := ""
	if tooLarge {
		issue = "metadata_too_large"
	} else {
		issue, err = nfoIssue(ctx, tx, binding.LibraryID, itemID, obs)
		if err != nil {
			return err
		}
	}
	if !same {
		if _, err := tx.ExecContext(ctx, `UPDATE metadata_input SET is_current=0 WHERE library_id=? AND item_id=? AND binding_id=? AND binding_epoch=? AND source_type='nfo' AND source_key=? AND is_current=1`, binding.LibraryID, itemID, stored.ID, stored.ProofEpoch, obs.SourceKey); err != nil {
			return err
		}
		inputID, err = randomID("metainput_")
		if err != nil {
			return err
		}
		status := "applied"
		if issue != "" {
			status = "candidate"
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO metadata_input(id,library_id,item_id,binding_id,binding_epoch,source_type,source_key,digest,parser_version,association_ambiguous,evidence_json,status,issue_code,is_current,created_at) VALUES (?,?,?,?,?,'nfo',?,?,?,?,?,?,?,1,?)`, inputID, binding.LibraryID, itemID, stored.ID, stored.ProofEpoch, obs.SourceKey, obs.Digest, obs.ParserVersion, obs.AssociationAmbiguous, string(evidence), status, issue, time.Now().UnixMilli()); err != nil {
			return err
		}
	} else {
		if oldStatus == "candidate" {
			if _, err := tx.ExecContext(ctx, "UPDATE metadata_input SET projection_stale=0 WHERE id=?", inputID); err != nil {
				return err
			}
			return nil
		}
		if _, err := tx.ExecContext(ctx, "UPDATE metadata_input SET projection_stale=0 WHERE id=?", inputID); err != nil {
			return err
		}
	}
	if issue != "" {
		revision, err := bumpEvidenceRevision(ctx, tx, binding.LibraryID, itemID)
		if err != nil {
			return err
		}
		return publishCatalogChange(ctx, tx, actor, binding.LibraryID, "catalog.item.metadata.candidate", catalogChange{ItemID: itemID, Revision: revision})
	}
	item := obs.Document.Items[0]
	before, err := readMetadataSnapshot(ctx, tx, binding.LibraryID, itemID)
	if err != nil {
		return err
	}
	var revision int64
	if err := tx.QueryRowContext(ctx, "SELECT revision FROM library_item WHERE library_id=? AND id=?", binding.LibraryID, itemID).Scan(&revision); err != nil {
		return err
	}
	current := map[string]metadataFieldState{}
	for _, field := range before.Fields {
		current[field.Name] = field
	}
	changed := false
	for name, values := range item.Fields {
		if metadataFieldLimit(name) == 0 {
			continue
		}
		values = nonemptyValues(values)
		if len(values) == 0 {
			continue
		}
		old, exists := current[name]
		if exists && (old.Locked || old.ExplicitClear) {
			continue
		}
		field := metadataFieldState{Name: name, Values: append([]string(nil), values...), Source: "nfo", SourceKey: bytes.Clone(obs.SourceKey), InputID: inputID, Revision: revision + 1}
		if !exists || !metadataStatesEqual(old, field) {
			if err := putMetadataField(ctx, tx, binding.LibraryID, itemID, field); err != nil {
				return err
			}
			changed = true
		}
	}
	if !same {
		credits := make([]mediametadata.Credit, 0, len(item.Credits))
		for _, credit := range item.Credits {
			if len(nonemptyValues(credit.Fields["name"])) == 1 {
				credits = append(credits, credit)
			}
		}
		ratings := make([]mediametadata.Rating, 0, len(item.Ratings))
		for _, rating := range item.Ratings {
			if rating.Value != nil || len(nonemptyValues(rating.Fields["value"])) > 0 {
				ratings = append(ratings, rating)
			}
		}
		artwork := make([]mediametadata.ArtworkDeclaration, 0, len(item.Artwork))
		for _, art := range item.Artwork {
			if strings.TrimSpace(art.Reference) != "" {
				artwork = append(artwork, art)
			}
		}
		if len(credits) > 0 {
			if err := replaceNFOCredits(ctx, tx, binding.LibraryID, itemID, stored.ID, obs.SourceKey, inputID, credits); err != nil {
				return err
			}
			changed = true
		}
		if len(ratings) > 0 {
			itemWithRatings := item
			itemWithRatings.Ratings = ratings
			if err := replaceNFORatings(ctx, tx, binding.LibraryID, itemID, obs.SourceKey, inputID, itemWithRatings); err != nil {
				return err
			}
			changed = true
		}
		if len(artwork) > 0 {
			if err := replaceNFOArtwork(ctx, tx, binding.LibraryID, itemID, stored.ID, obs.SourceKey, inputID, artwork); err != nil {
				return err
			}
			changed = true
		}
		if len(item.ExternalIDs) > 0 {
			added, err := replaceNFOExternalIDs(ctx, tx, binding.LibraryID, itemID, obs.SourceKey, inputID, item)
			if err != nil {
				return err
			}
			changed = changed || added
		}
	}
	if !changed {
		if !same {
			revision, err := bumpEvidenceRevision(ctx, tx, binding.LibraryID, itemID)
			if err != nil {
				return err
			}
			return publishCatalogChange(ctx, tx, actor, binding.LibraryID, "catalog.item.metadata.evidence", catalogChange{ItemID: itemID, Revision: revision})
		}
		return nil
	}
	if err := updateMetadataItem(ctx, tx, binding.LibraryID, itemID, revision); err != nil {
		return err
	}
	after, err := readMetadataSnapshot(ctx, tx, binding.LibraryID, itemID)
	if err != nil {
		return err
	}
	return recordMetadataChange(ctx, tx, actor, binding.LibraryID, itemID, "nfo", revision+1, before, after)
}

func bumpEvidenceRevision(ctx context.Context, tx *sql.Tx, libraryID, itemID string) (int64, error) {
	result, err := tx.ExecContext(ctx, "UPDATE library_item SET revision=revision+1 WHERE library_id=? AND id=?", libraryID, itemID)
	if err != nil {
		return 0, err
	}
	n, err := result.RowsAffected()
	if err != nil {
		return 0, err
	}
	if n != 1 {
		return 0, ErrNotFound
	}
	var revision int64
	err = tx.QueryRowContext(ctx, "SELECT revision FROM library_item WHERE library_id=? AND id=?", libraryID, itemID).Scan(&revision)
	return revision, err
}

func findCreditPerson(ctx context.Context, tx *sql.Tx, libraryID string, ids []mediametadata.ExternalID) (string, bool, error) {
	matched := ""
	for _, id := range ids {
		provider, value, ok := safeExternalID(id)
		if !ok {
			continue
		}
		var personID string
		err := tx.QueryRowContext(ctx, "SELECT person_id FROM person_external_id WHERE library_id=? AND provider=? AND external_id=?", libraryID, provider, value).Scan(&personID)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return "", false, err
		}
		if matched != "" && matched != personID {
			return "", true, nil
		}
		matched = personID
	}
	return matched, false, nil
}

// A shared provider ID may link declarations, but contradictory IDs of a
// second provider in the same connected person are evidence for review.
func creditIdentityConflict(ctx context.Context, tx *sql.Tx, libraryID string, credits []mediametadata.Credit) (bool, error) {
	parent := make([]int, len(credits))
	for i := range parent {
		parent[i] = i
	}
	root := func(i int) int {
		for parent[i] != i {
			parent[i] = parent[parent[i]]
			i = parent[i]
		}
		return i
	}
	join := func(a, b int) {
		a, b = root(a), root(b)
		if a != b {
			parent[b] = a
		}
	}
	seen := map[string]int{}
	personSeen := map[string]int{}
	identities := make([]map[string]string, len(credits))
	for i, credit := range credits {
		identities[i] = map[string]string{}
		for _, id := range credit.ExternalIDs {
			provider, value, ok := safeExternalID(id)
			if !ok {
				continue
			}
			identities[i][provider] = value
			key := provider + "\x00" + value
			if other, ok := seen[key]; ok {
				join(i, other)
			} else {
				seen[key] = i
			}
		}
		person, conflict, err := findCreditPerson(ctx, tx, libraryID, credit.ExternalIDs)
		if err != nil {
			return false, err
		}
		if conflict {
			return true, nil
		}
		if person == "" {
			continue
		}
		if other, ok := personSeen[person]; ok {
			join(i, other)
		} else {
			personSeen[person] = i
		}
		rows, err := tx.QueryContext(ctx, "SELECT provider,external_id FROM person_external_id WHERE library_id=? AND person_id=?", libraryID, person)
		if err != nil {
			return false, err
		}
		for rows.Next() {
			var provider, value string
			if err := rows.Scan(&provider, &value); err != nil {
				_ = rows.Close()
				return false, err
			}
			if old, ok := identities[i][provider]; ok && old != value {
				_ = rows.Close()
				return true, nil
			}
			identities[i][provider] = value
		}
		if err := rows.Err(); err != nil {
			_ = rows.Close()
			return false, err
		}
		if err := rows.Close(); err != nil {
			return false, err
		}
	}
	groups := map[int]map[string]string{}
	for i, ids := range identities {
		key := root(i)
		if groups[key] == nil {
			groups[key] = map[string]string{}
		}
		for provider, value := range ids {
			if old, ok := groups[key][provider]; ok && old != value {
				return true, nil
			}
			groups[key][provider] = value
		}
	}
	return false, nil
}

func replaceNFOCredits(ctx context.Context, tx *sql.Tx, libraryID, itemID, bindingID string, sourceKey []byte, inputID string, credits []mediametadata.Credit) error {
	if _, err := tx.ExecContext(ctx, "UPDATE item_credit SET active=0 WHERE library_id=? AND item_id=? AND source_key=? AND active=1", libraryID, itemID, sourceKey); err != nil {
		return err
	}
	occurrences := map[[32]byte]int{}
	for position, credit := range credits {
		names := nonemptyValues(credit.Fields["name"])
		if len(names) != 1 || validateMetadataValues("title", names) != nil {
			continue
		}
		roles := nonemptyValues(credit.Fields["role"])
		if len(roles) > 128 {
			return fmt.Errorf("%w: credit roles", ErrInvalid)
		}
		for _, role := range roles {
			if err := validateMetadataValues("title", []string{role}); err != nil {
				return err
			}
		}
		personID, conflict, err := findCreditPerson(ctx, tx, libraryID, credit.ExternalIDs)
		if err != nil {
			return err
		}
		if conflict {
			return ErrRevisionConflict
		}
		var explicit bool
		for _, id := range credit.ExternalIDs {
			_, _, ok := safeExternalID(id)
			explicit = explicit || ok
		}
		if personID == "" && !explicit {
			encoded, err := json.Marshal(credit)
			if err != nil {
				return err
			}
			fingerprint := sha256.Sum256(encoded)
			ordinal := occurrences[fingerprint]
			occurrences[fingerprint]++
			keyHash := sha256.Sum256(append(fingerprint[:], []byte(fmt.Sprintf(":%d", ordinal))...))
			declarationSource := append(append([]byte(bindingID), 0), sourceKey...)
			err = tx.QueryRowContext(ctx, "SELECT id FROM library_person WHERE library_id=? AND declaration_source=? AND declaration_hash=?", libraryID, declarationSource, keyHash[:]).Scan(&personID)
			if err != nil && !errors.Is(err, sql.ErrNoRows) {
				return err
			}
			if errors.Is(err, sql.ErrNoRows) {
				personID, err = randomID("person_")
				if err != nil {
					return err
				}
				if _, err := tx.ExecContext(ctx, "INSERT INTO library_person(id,library_id,name,declaration_source,declaration_hash) VALUES (?,?,?,?,?)", personID, libraryID, names[0], declarationSource, keyHash[:]); err != nil {
					return err
				}
			}
		}
		if personID == "" {
			personID, err = randomID("person_")
			if err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, "INSERT INTO library_person(id,library_id,name) VALUES (?,?,?)", personID, libraryID, names[0]); err != nil {
				return err
			}
		}
		for _, id := range credit.ExternalIDs {
			provider, value, ok := safeExternalID(id)
			if !ok {
				continue
			}
			if _, err := tx.ExecContext(ctx, "INSERT INTO person_external_id(library_id,provider,external_id,person_id) VALUES (?,?,?,?) ON CONFLICT(library_id,provider,external_id) DO NOTHING", libraryID, provider, value, personID); err != nil {
				return err
			}
		}
		id, err := randomID("credit_")
		if err != nil {
			return err
		}
		role := ""
		if len(roles) > 0 {
			role = roles[0]
		}
		encoded, err := json.Marshal(roles)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO item_credit(id,library_id,item_id,person_id,display_name,kind,role,roles_json,position,source_key,input_id,active) VALUES (?,?,?,?,?,?,?,?,?,?,?,1)`, id, libraryID, itemID, personID, names[0], credit.Kind, role, string(encoded), position, sourceKey, inputID); err != nil {
			return err
		}
	}
	return nil
}

func replaceNFORatings(ctx context.Context, tx *sql.Tx, libraryID, itemID string, sourceKey []byte, inputID string, item mediametadata.Item) error {
	if _, err := tx.ExecContext(ctx, "UPDATE rating_observation SET active=0 WHERE library_id=? AND item_id=? AND source_key=? AND active=1", libraryID, itemID, sourceKey); err != nil {
		return err
	}
	for position, rating := range item.Ratings {
		// Numeric parse evidence is retained; URL-like untrusted provider text
		// is not persisted as it can contain access tokens.
		rating = sanitizeRating(rating)
		encoded, err := json.Marshal(rating)
		if err != nil {
			return err
		}
		id, err := randomID("rating_")
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO rating_observation(id,library_id,item_id,source_key,input_id,scope,rating_json,position,active) VALUES (?,?,?,?,?,?,?,?,1)`, id, libraryID, itemID, sourceKey, inputID, string(item.Kind), string(encoded), position); err != nil {
			return err
		}
	}
	return nil
}

func replaceNFOArtwork(ctx context.Context, tx *sql.Tx, libraryID, itemID, bindingID string, sourceKey []byte, inputID string, artwork []mediametadata.ArtworkDeclaration) error {
	if _, err := tx.ExecContext(ctx, "UPDATE artwork_declaration SET active=0 WHERE library_id=? AND item_id=? AND source_key=? AND active=1", libraryID, itemID, sourceKey); err != nil {
		return err
	}
	for position, art := range artwork {
		role := art.Role
		if role == "" {
			role = "unknown"
		}
		status, ref := classifyArtworkReference(art.Reference)
		var privateRef any
		if ref != nil {
			privateRef = ref
		}
		id, err := randomID("art_")
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO artwork_declaration(id,library_id,item_id,binding_id,source_key,input_id,private_ref,role,status,position,active) VALUES (?,?,?,?,?,?,?,?,?,?,1)`, id, libraryID, itemID, bindingID, sourceKey, inputID, privateRef, role, status, position); err != nil {
			return err
		}
	}
	return nil
}

func replaceNFOExternalIDs(ctx context.Context, tx *sql.Tx, libraryID, itemID string, sourceKey []byte, inputID string, item mediametadata.Item) (bool, error) {
	valid := make([]mediametadata.ExternalID, 0, len(item.ExternalIDs))
	for _, external := range item.ExternalIDs {
		_, _, ok := safeExternalID(external)
		if ok {
			valid = append(valid, external)
		}
	}
	if len(valid) == 0 {
		return false, nil
	}
	if _, err := tx.ExecContext(ctx, "UPDATE item_external_id SET active=0 WHERE library_id=? AND item_id=? AND source_key=? AND active=1", libraryID, itemID, sourceKey); err != nil {
		return false, err
	}
	for _, external := range valid {
		provider, value, _ := safeExternalID(external)
		id, err := randomID("external_")
		if err != nil {
			return false, err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO item_external_id(id,library_id,item_id,provider,entity_kind,external_id,source_key,input_id,active) VALUES (?,?,?,?,?,?,?,?,1)`, id, libraryID, itemID, provider, string(item.Kind), value, sourceKey, inputID); err != nil {
			return false, err
		}
	}
	return true, nil
}
