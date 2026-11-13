package fbhttp

import (
	"context"
	"fmt"
	"net/http"
	"strconv"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
	"github.com/gorilla/mux"
)

type mediaLibraryCreateBody struct {
	OperationID string `json:"operationId"`
	Name        string `json:"name"`
}

type mediaLibraryChangeBody struct {
	OperationID      string `json:"operationId"`
	ExpectedRevision int64  `json:"expectedRevision"`
	Name             string `json:"name,omitempty"`
}

type mediaLibraryMemberBody struct {
	OperationID      string                  `json:"operationId"`
	ExpectedRevision int64                   `json:"expectedRevision"`
	Role             medialibrary.MemberRole `json:"role,omitempty"`
}

type mediaLibrarySourceBody struct {
	OperationID      string `json:"operationId"`
	ExpectedRevision int64  `json:"expectedRevision"`
	WireRoot         string `json:"wireRoot"`
}

type mediaLibrarySourceSummary struct {
	ID                    string `json:"id"`
	SourceID              string `json:"sourceId"`
	LibraryID             string `json:"libraryId"`
	WireRoot              string `json:"wireRoot,omitempty"`
	DisplayRoot           string `json:"displayRoot,omitempty"`
	Access                string `json:"access"`
	Revision              int64  `json:"revision"`
	LibraryRevision       int64  `json:"libraryRevision"`
	AuthorizationRevision int64  `json:"authorizationRevision"`
}

// This owner-only management view may display an explicitly bound root but
// never exposes source configuration, native paths or credentials.
func sourceSummary(source *medialibrary.LibrarySource) mediaLibrarySourceSummary {
	return mediaLibrarySourceSummary{ID: source.ID, SourceID: source.Source.ID, LibraryID: source.LibraryID, Access: "available",
		WireRoot: files.EncodeWirePath(string(source.RootLocator)), DisplayRoot: files.DisplayPath(string(source.RootLocator)),
		Revision: source.Revision, LibraryRevision: source.LibraryRevision, AuthorizationRevision: source.Source.AuthorizationRevision}
}

var mediaLibraryCreateHandler = mediaScanManagement(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var body mediaLibraryCreateBody
	if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) {
		return http.StatusBadRequest, fmt.Errorf("媒体库参数无效")
	}
	library, err := d.store.MediaLibrary.CreateLibraryWithOperation(r.Context(), d.user.ID, body.OperationID, body.Name)
	if err != nil {
		return mediaScanError(err)
	}
	return renderJSONStatus(w, librarySummary(library), http.StatusCreated)
})

func mediaLibraryChangeHandler(action string) handleFunc {
	return mediaScanManagement(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body mediaLibraryChangeBody
		if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision < 1 {
			return http.StatusBadRequest, fmt.Errorf("媒体库参数无效")
		}
		if action != "update" && body.Name != "" {
			return http.StatusBadRequest, fmt.Errorf("媒体库参数无效")
		}
		id := mux.Vars(r)["id"]
		var library *medialibrary.Library
		var err error
		switch action {
		case "update":
			library, err = d.store.MediaLibrary.UpdateLibraryWithOperation(r.Context(), d.user.ID, id, body.ExpectedRevision, body.OperationID, body.Name)
		case "archive":
			library, err = d.store.MediaLibrary.ArchiveLibraryWithOperation(r.Context(), d.user.ID, id, body.ExpectedRevision, body.OperationID)
		case "restore":
			library, err = d.store.MediaLibrary.RestoreLibraryWithOperation(r.Context(), d.user.ID, id, body.ExpectedRevision, body.OperationID)
		default:
			return http.StatusInternalServerError, fmt.Errorf("媒体库操作不可用")
		}
		if err != nil {
			return mediaScanError(err)
		}
		return renderJSON(w, r, librarySummary(library))
	})
}

func mediaLibraryMemberHandler(grant bool) handleFunc {
	return mediaScanManagement(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body mediaLibraryMemberBody
		if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision < 1 {
			return http.StatusBadRequest, fmt.Errorf("成员参数无效")
		}
		userValue, err := strconv.ParseUint(mux.Vars(r)["userId"], 10, 32)
		if err != nil || userValue == 0 {
			return http.StatusBadRequest, fmt.Errorf("成员参数无效")
		}
		memberID := uint(userValue)
		library, err := d.store.MediaLibrary.GetLibrary(r.Context(), d.user.ID, mux.Vars(r)["id"])
		if err != nil {
			return mediaScanError(err)
		}
		if library.OwnerUserID != d.user.ID || library.Role != medialibrary.RoleOwner {
			return http.StatusForbidden, fmt.Errorf("没有此媒体库操作权限")
		}
		if grant {
			if mediaSharingDisabled(d) {
				return http.StatusForbidden, fmt.Errorf("此实例已关闭影视库共享")
			}
			if body.Role != medialibrary.RoleViewer && body.Role != medialibrary.RoleEditor {
				return http.StatusBadRequest, fmt.Errorf("成员角色无效")
			}
			if member, err := d.store.Users.Get(d.server.Root, memberID); err != nil || member == nil {
				return http.StatusNotFound, fmt.Errorf("成员不存在")
			}
		} else if body.Role != "" {
			return http.StatusBadRequest, fmt.Errorf("成员参数无效")
		}
		var change *medialibrary.MemberGrant
		if grant {
			change, err = d.store.MediaLibrary.GrantMemberWithOperation(r.Context(), d.user.ID, mux.Vars(r)["id"], body.ExpectedRevision, body.OperationID, memberID, body.Role)
		} else {
			change, err = d.store.MediaLibrary.RevokeMemberWithOperation(r.Context(), d.user.ID, mux.Vars(r)["id"], body.ExpectedRevision, body.OperationID, memberID)
		}
		if err != nil {
			return mediaScanError(err)
		}
		return renderJSON(w, r, struct {
			UserID          uint                    `json:"userId"`
			Role            medialibrary.MemberRole `json:"role"`
			Revision        int64                   `json:"revision"`
			LibraryRevision int64                   `json:"libraryRevision"`
			Revoked         bool                    `json:"revoked"`
		}{change.UserID, change.Role, change.Revision, change.LibraryRevision, change.RevokedAt != nil})
	})
}

var mediaLibraryCreateSourceHandler = mediaScanManagement(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	var body mediaLibrarySourceBody
	if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedRevision < 1 || body.WireRoot == "" {
		return http.StatusBadRequest, fmt.Errorf("来源参数无效")
	}
	root, err := decodeResourceWirePath(body.WireRoot)
	if err != nil {
		return http.StatusBadRequest, fmt.Errorf("来源路径无效")
	}
	library, err := d.store.MediaLibrary.GetLibrary(r.Context(), d.user.ID, mux.Vars(r)["id"])
	if err != nil {
		return mediaScanError(err)
	}
	if library.OwnerUserID != d.user.ID || library.Role != medialibrary.RoleOwner {
		return http.StatusForbidden, fmt.Errorf("没有此媒体库操作权限")
	}
	if library.ArchivedAt != nil {
		return mediaScanError(medialibrary.ErrArchived)
	}
	if !d.Check(root) {
		return http.StatusForbidden, fmt.Errorf("没有来源目录权限")
	}
	local, err := mediasource.NewLocal(root, func(ctx context.Context) (mediasource.LocalAuthority, error) {
		return resolveLocalMediaConnection(ctx, d.store, d.server, d.user.ID, localMediaWorkspaceConfiguration)
	})
	if err != nil {
		return mediaScanError(err)
	}
	handle, err := local.Open(r.Context(), "", true)
	if err != nil {
		return mediaScanError(err)
	}
	if err := handle.Close(); err != nil {
		return mediaScanError(err)
	}
	binding, err := d.store.MediaLibrary.CreateLocalSourceWithOperation(r.Context(), d.user.ID, library.ID, body.ExpectedRevision, body.OperationID, localMediaWorkspaceConfiguration, []byte(root))
	if err != nil {
		return mediaScanError(err)
	}
	return renderJSONStatus(w, sourceSummary(binding), http.StatusCreated)
})

var mediaLibrarySourcesHandler = mediaScanManagement(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	options, err := mediaSourceListOptions(r)
	if err != nil {
		return http.StatusBadRequest, fmt.Errorf("来源分页参数无效")
	}
	library, err := d.store.MediaLibrary.GetLibrary(r.Context(), d.user.ID, mux.Vars(r)["id"])
	if err != nil {
		return mediaScanError(err)
	}
	if library.OwnerUserID != d.user.ID || library.Role != medialibrary.RoleOwner {
		return http.StatusNotFound, fmt.Errorf("媒体库不存在或不可访问")
	}
	if library.ArchivedAt != nil {
		return mediaScanError(medialibrary.ErrArchived)
	}
	page, err := d.store.MediaLibrary.ListLibrarySources(r.Context(), d.user.ID, mux.Vars(r)["id"], options.cursor, options.limit)
	if err != nil {
		return mediaScanError(err)
	}
	items := make([]mediaLibrarySourceSummary, 0, len(page.Items))
	for i := range page.Items {
		summary := sourceSummary(&page.Items[i])
		if _, err := mediascan.CheckBinding(r.Context(), d.store.MediaLibrary, d.user.ID, page.Items[i].Ref(), mediaScanResolver(d)); err != nil {
			// Retain a manageable source row without revealing a root that is no
			// longer within the owner's current scope or cannot be safely opened.
			summary.WireRoot, summary.DisplayRoot, summary.Access = "", "", "unavailable"
		}
		items = append(items, summary)
	}
	return renderJSON(w, r, struct {
		Items      []mediaLibrarySourceSummary `json:"items"`
		NextCursor string                      `json:"nextCursor,omitempty"`
	}{items, page.NextCursor})
})

func mediaSourceListOptions(r *http.Request) (struct {
	cursor string
	limit  int
}, error) {
	result := struct {
		cursor string
		limit  int
	}{cursor: r.URL.Query().Get("cursor"), limit: 50}
	if len(result.cursor) > 200 {
		return result, fmt.Errorf("invalid cursor")
	}
	if raw := r.URL.Query().Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 1 || limit > 100 {
			return result, fmt.Errorf("invalid limit")
		}
		result.limit = limit
	}
	return result, nil
}
