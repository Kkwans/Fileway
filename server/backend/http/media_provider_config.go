package fbhttp

import (
	"errors"
	"net/http"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/gorilla/mux"
)

func providerConfig(fn handleFunc) handleFunc {
	return catalogLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if d.deviceSession != "" {
			return http.StatusForbidden, errors.New("device configuration denied")
		}
		return fn(w, r, d)
	})
}

func providerAdmin(d *data) (bool, error) {
	if d.authCheck != nil && d.authCheck() != nil {
		return false, medialibrary.ErrForbidden
	}
	user, err := d.store.Users.Get(d.server.Root, d.user.ID)
	if err != nil || user == nil || user.ID != d.user.ID {
		return false, medialibrary.ErrForbidden
	}
	return user.Perm.Admin, nil
}

func providerError(w http.ResponseWriter, err error) (int, error) {
	if errors.Is(err, medialibrary.ErrRevisionConflict) {
		return writeCatalogError(w, http.StatusConflict, "revision_conflict", "配置已变化，请刷新", true)
	}
	return catalogError(w, err)
}

func providerUnknown(w http.ResponseWriter) (int, error) {
	return writeCatalogError(w, http.StatusServiceUnavailable, "write_outcome_unknown", "操作结果待确认，请使用原操作记录重试", true)
}

func providerPostCheck(d *data, requireAdmin bool) error {
	admin, err := providerAdmin(d)
	if err != nil || requireAdmin && !admin {
		return medialibrary.ErrForbidden
	}
	return nil
}

func providerPageLimit(r *http.Request) (int, error) {
	value := r.URL.Query().Get("limit")
	if value == "" {
		return 50, nil
	}
	limit, err := strconv.Atoi(value)
	if err != nil || limit < 1 || limit > 100 {
		return 0, medialibrary.ErrInvalid
	}
	return limit, nil
}

func mediaProviderStatusHandler(scheduler *mediascan.Scheduler) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if _, err := providerAdmin(d); err != nil {
			return providerError(w, err)
		}
		return renderJSON(w, r, struct {
			CredentialStoreAvailable   bool `json:"credentialStoreAvailable"`
			CandidateSearchAvailable   bool `json:"candidateSearchAvailable"`
			MatchConfirmationAvailable bool `json:"matchConfirmationAvailable"`
			NetworkJobsAvailable       bool `json:"networkJobsAvailable"`
		}{
			CredentialStoreAvailable:   d.store.MediaProviderVault != nil,
			CandidateSearchAvailable:   d.store.MediaProviderVault != nil && d.store.TMDBHTTPClient != nil,
			MatchConfirmationAvailable: d.store.MediaProviderVault != nil && d.store.TMDBHTTPClient != nil,
			NetworkJobsAvailable:       scheduler.MetadataAvailable(r.Context()),
		})
	})
}

type providerConnectionBody struct {
	OperationID      string `json:"operationId"`
	OwnerKind        string `json:"ownerKind"`
	ExpectedRevision *int64 `json:"expectedRevision"`
	Token            string `json:"token"`
}

func validProviderToken(value string) bool {
	return len(value) > 0 && len(value) <= 4096 && utf8.ValidString(value) &&
		strings.IndexFunc(value, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) }) < 0
}

var mediaProviderConnectionsHandler = providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	admin, err := providerAdmin(d)
	if err != nil {
		return providerError(w, err)
	}
	if r.Method == http.MethodGet {
		limit, err := providerPageLimit(r)
		if err != nil {
			return providerError(w, err)
		}
		page, err := d.store.MediaLibrary.ListProviderConnections(r.Context(), d.user.ID, admin, mediaSharingDisabled(d), r.URL.Query().Get("cursor"), limit)
		if err != nil {
			return providerError(w, err)
		}
		if err = providerPostCheck(d, admin); err != nil {
			return providerError(w, err)
		}
		if err = d.store.MediaLibrary.VerifyProviderConnections(r.Context(), d.user.ID, admin, mediaSharingDisabled(d), page.Items); err != nil {
			return providerError(w, err)
		}
		if err = providerPostCheck(d, admin); err != nil {
			return providerError(w, err)
		}
		return renderJSON(w, r, page)
	}
	var body providerConnectionBody
	if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision != nil ||
		(body.OwnerKind != "user" && body.OwnerKind != "instance") || !validProviderToken(body.Token) {
		return providerError(w, medialibrary.ErrInvalid)
	}
	if body.OwnerKind == "instance" && !admin {
		return providerError(w, medialibrary.ErrForbidden)
	}
	if d.store.MediaProviderVault == nil {
		return writeCatalogError(w, http.StatusServiceUnavailable, "credential_store_unavailable", "资料凭据存储暂不可用", true)
	}
	id, err := medialibrary.ProviderConnectionID(d.user.ID, body.OperationID)
	if err != nil {
		return providerError(w, err)
	}
	context, err := medialibrary.ProviderCredentialContext(id)
	if err != nil {
		return providerError(w, err)
	}
	ciphertext, fingerprint, err := d.store.MediaProviderVault.Seal(context, body.Token)
	body.Token = ""
	if err != nil {
		return writeCatalogError(w, http.StatusServiceUnavailable, "credential_store_unavailable", "资料凭据存储暂不可用", true)
	}
	value, err := d.store.MediaLibrary.PutProviderConnection(r.Context(), d.user.ID, admin, id, body.OwnerKind, body.OperationID, 0,
		medialibrary.SealedProviderCredential{Ciphertext: ciphertext, Fingerprint: fingerprint})
	if err != nil {
		return providerError(w, err)
	}
	if err = providerPostCheck(d, body.OwnerKind == "instance"); err != nil {
		return providerUnknown(w)
	}
	return renderJSONStatus(w, value, http.StatusCreated)
})

var mediaProviderConnectionHandler = providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	admin, err := providerAdmin(d)
	if err != nil {
		return providerError(w, err)
	}
	id := mux.Vars(r)["connectionId"]
	value, err := d.store.MediaLibrary.GetManagedProviderConnection(r.Context(), d.user.ID, admin, id)
	if err != nil {
		return providerError(w, err)
	}
	var body providerConnectionBody
	if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision == nil || *body.ExpectedRevision < 1 || body.OwnerKind != "" {
		return providerError(w, medialibrary.ErrInvalid)
	}
	if r.Method == http.MethodDelete {
		if body.Token != "" {
			return providerError(w, medialibrary.ErrInvalid)
		}
		value, err = d.store.MediaLibrary.ClearProviderConnection(r.Context(), d.user.ID, admin, id, body.OperationID, *body.ExpectedRevision)
		if err != nil {
			return providerError(w, err)
		}
		if err = providerPostCheck(d, value.OwnerKind == "instance"); err != nil {
			return providerUnknown(w)
		}
		return renderJSON(w, r, value)
	}
	if !validProviderToken(body.Token) {
		return providerError(w, medialibrary.ErrInvalid)
	}
	if d.store.MediaProviderVault == nil {
		return writeCatalogError(w, http.StatusServiceUnavailable, "credential_store_unavailable", "资料凭据存储暂不可用", true)
	}
	context, err := medialibrary.ProviderCredentialContext(id)
	if err != nil {
		return providerError(w, err)
	}
	ciphertext, fingerprint, err := d.store.MediaProviderVault.Seal(context, body.Token)
	body.Token = ""
	if err != nil {
		return writeCatalogError(w, http.StatusServiceUnavailable, "credential_store_unavailable", "资料凭据存储暂不可用", true)
	}
	value, err = d.store.MediaLibrary.PutProviderConnection(r.Context(), d.user.ID, admin, id, value.OwnerKind, body.OperationID, *body.ExpectedRevision,
		medialibrary.SealedProviderCredential{Ciphertext: ciphertext, Fingerprint: fingerprint})
	if err != nil {
		return providerError(w, err)
	}
	if err = providerPostCheck(d, value.OwnerKind == "instance"); err != nil {
		return providerUnknown(w)
	}
	return renderJSON(w, r, value)
})

type providerGrantBody struct {
	OperationID      string `json:"operationId"`
	ExpectedRevision *int64 `json:"expectedRevision"`
	UserID           *uint  `json:"userId"`
	LibraryID        string `json:"libraryId"`
}

var mediaProviderGrantsHandler = providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	admin, err := providerAdmin(d)
	if err != nil || !admin {
		return providerError(w, medialibrary.ErrForbidden)
	}
	id := mux.Vars(r)["connectionId"]
	if r.Method == http.MethodGet {
		limit, err := providerPageLimit(r)
		if err != nil {
			return providerError(w, err)
		}
		page, err := d.store.MediaLibrary.ListProviderGrants(r.Context(), d.user.ID, admin, id, r.URL.Query().Get("afterKind"), r.URL.Query().Get("afterId"), limit)
		if err != nil {
			return providerError(w, err)
		}
		if err = providerPostCheck(d, true); err != nil {
			return providerError(w, err)
		}
		return renderJSON(w, r, page)
	}
	var body providerGrantBody
	if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision == nil || *body.ExpectedRevision < 1 ||
		(body.UserID == nil) == (body.LibraryID == "") {
		return providerError(w, medialibrary.ErrInvalid)
	}
	kind, subject := "library", body.LibraryID
	if body.UserID != nil {
		if *body.UserID == 0 {
			return providerError(w, medialibrary.ErrInvalid)
		}
		if user, err := d.store.Users.Get(d.server.Root, *body.UserID); err != nil || user == nil {
			return providerError(w, medialibrary.ErrNotFound)
		}
		kind, subject = "user", strconv.FormatUint(uint64(*body.UserID), 10)
	}
	value, err := d.store.MediaLibrary.SetProviderGrant(r.Context(), d.user.ID, admin, id, kind, subject, body.OperationID, *body.ExpectedRevision, r.Method == http.MethodPut)
	if err != nil {
		return providerError(w, err)
	}
	if err = providerPostCheck(d, true); err != nil {
		return providerUnknown(w)
	}
	return renderJSON(w, r, value)
})

type libraryMetadataPolicyBody struct {
	OperationID      string `json:"operationId"`
	ExpectedRevision *int64 `json:"expectedRevision"`
	Mode             string `json:"mode"`
	ConnectionID     string `json:"connectionId"`
	Language         string `json:"language"`
	Region           string `json:"region"`
	AllowEditorJobs  *bool  `json:"allowEditorJobs"`
}

var mediaLibraryMetadataPolicyHandler = providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if _, err := providerAdmin(d); err != nil {
		return providerError(w, err)
	}
	library, err := itemLibrary(r, d)
	if err != nil {
		return providerError(w, err)
	}
	if r.Method == http.MethodGet {
		value, err := d.store.MediaLibrary.GetLibraryMetadataPolicy(r.Context(), d.user.ID, library.ID)
		if err != nil {
			return providerError(w, err)
		}
		current, err := itemLibrary(r, d)
		if err != nil || current.Revision != library.Revision || current.Role != library.Role {
			return providerError(w, medialibrary.ErrRevisionConflict)
		}
		return renderJSON(w, r, value)
	}
	var body libraryMetadataPolicyBody
	if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision == nil || *body.ExpectedRevision < 0 || body.AllowEditorJobs == nil {
		return providerError(w, medialibrary.ErrInvalid)
	}
	value, err := d.store.MediaLibrary.SetLibraryMetadataPolicy(r.Context(), d.user.ID, library.ID, body.OperationID, *body.ExpectedRevision,
		medialibrary.LibraryMetadataPolicy{Mode: body.Mode, ConnectionID: body.ConnectionID, Language: body.Language, Region: body.Region, AllowEditorJobs: *body.AllowEditorJobs})
	if err != nil {
		return providerError(w, err)
	}
	current, err := itemLibrary(r, d)
	if err != nil || current.Role != medialibrary.RoleOwner || current.Revision != library.Revision || providerPostCheck(d, false) != nil {
		return providerUnknown(w)
	}
	return renderJSON(w, r, value)
})
