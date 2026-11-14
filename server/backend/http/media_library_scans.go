package fbhttp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/Kkwans/nas-file-browser/backend/mediasource"
	"github.com/gorilla/mux"
)

type scanScheduler interface {
	Ready(context.Context) error
	Wake()
	EnqueueScanByBindingID(context.Context, uint, string, string, int64, string, int64) (*medialibrary.ScanJob, error)
	CancelScanWithOperation(context.Context, uint, string, int64, string) (*medialibrary.ScanJob, error)
	RetryScanWithOperation(context.Context, uint, string, int64, string) (*medialibrary.ScanJob, error)
}

type mediaScanBody struct {
	BindingID        string `json:"bindingId"`
	OperationID      string `json:"operationId"`
	RulesRevision    int64  `json:"rulesRevision"`
	ExpectedRevision int64  `json:"expectedRevision"`
}

type mediaScanRetryBody struct {
	OperationID        string `json:"operationId"`
	ExpectedGeneration int64  `json:"expectedGeneration"`
}

type mediaScanCancelBody struct {
	OperationID        string `json:"operationId"`
	ExpectedGeneration int64  `json:"expectedGeneration"`
}

type mediaScanSummary struct {
	ID            string `json:"id"`
	LibraryID     string `json:"libraryId"`
	Status        string `json:"status"`
	ErrorCode     string `json:"errorCode,omitempty"`
	Generation    int64  `json:"generation"`
	Attempt       int64  `json:"attempt"`
	RulesRevision int64  `json:"rulesRevision"`
	Phase         string `json:"phase"`
	Processed     int64  `json:"processed"`
	CreatedAt     int64  `json:"createdAt"`
	UpdatedAt     int64  `json:"updatedAt"`
}

// This advertises only the local scan contract and coordinator state, never
// the unfinished media-library/playback capability or permission to any root.
func mediaScanSupportHandler(scheduler scanScheduler) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		w.Header().Set("Cache-Control", "no-store")
		if d.deviceSession != "" {
			return http.StatusForbidden, fmt.Errorf("设备凭据不能管理扫描")
		}
		result := struct {
			LocalRulesRevision int64  `json:"localRulesRevision"`
			Available          bool   `json:"available"`
			Reason             string `json:"reason,omitempty"`
		}{LocalRulesRevision: mediascan.LocalRulesRevision}
		switch {
		case d.store.MediaLibrary == nil:
			result.Reason = "not_enabled"
		case scheduler == nil || scheduler.Ready(r.Context()) != nil:
			result.Reason = "coordinator_unavailable"
		default:
			result.Available = true
		}
		return renderJSON(w, r, result)
	})
}

// Do not expose source locators, native object keys, configuration references,
// or a checkpoint's LastObjectKey through a task response.
func scanSummary(job *medialibrary.ScanJob) mediaScanSummary {
	return mediaScanSummary{ID: job.ID, LibraryID: job.LibraryID, Status: job.Status, ErrorCode: job.ErrorCode, Generation: job.Generation, Attempt: job.Attempt,
		RulesRevision: job.RulesRevision, Phase: job.Checkpoint.Phase, Processed: job.Checkpoint.Processed, CreatedAt: job.CreatedAt, UpdatedAt: job.UpdatedAt}
}

func mediaScanManagement(fn handleFunc) handleFunc {
	return mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		// Device credentials deliberately do not inherit library management even
		// if their account happens to own a library.
		if d.deviceSession != "" {
			return http.StatusForbidden, fmt.Errorf("设备凭据不能管理扫描")
		}
		return fn(w, r, d)
	})
}

func decodeMediaScanBody(w http.ResponseWriter, r *http.Request, target any) error {
	if r.Body == nil {
		return fs.ErrInvalid
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return fs.ErrInvalid
	}
	if err := decoder.Decode(new(any)); !errors.Is(err, io.EOF) {
		return fs.ErrInvalid
	}
	return nil
}

func validScanID(id string) bool {
	return id != "" && len(id) <= 200 && strings.TrimSpace(id) == id
}

func mediaScanResolver(d *data) mediascan.ResolveOwnerAuthority {
	return func(ctx context.Context, binding *medialibrary.LibrarySource) (mediasource.LocalAuthority, error) {
		authority, _, err := resolveAttestedMediaConnection(ctx, d.store, d.server, binding.Source.OwnerUserID, binding.Source.ConfigurationRef, binding.RootLocator, binding.Proof)
		return authority, err
	}
}

func mediaScanCreateHandler(scheduler scanScheduler) handleFunc {
	return mediaScanManagement(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if scheduler == nil || scheduler.Ready(r.Context()) != nil {
			return http.StatusServiceUnavailable, fmt.Errorf("扫描服务暂不可用")
		}
		var body mediaScanBody
		if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.BindingID) || !validScanID(body.OperationID) || body.ExpectedRevision < 1 || body.RulesRevision != mediascan.LocalRulesRevision {
			return http.StatusBadRequest, fmt.Errorf("扫描参数无效")
		}
		binding, err := d.store.MediaLibrary.GetLibrarySourceByID(r.Context(), d.user.ID, body.BindingID)
		if err != nil {
			return mediaScanError(err)
		}
		if binding.LibraryID != mux.Vars(r)["id"] {
			return http.StatusNotFound, fmt.Errorf("来源不存在或不可访问")
		}
		if _, err := mediascan.CheckBinding(r.Context(), d.store.MediaLibrary, d.user.ID, binding.Ref(), mediaScanResolver(d)); err != nil {
			return mediaScanError(err)
		}
		if scheduler.Ready(r.Context()) != nil {
			return http.StatusServiceUnavailable, fmt.Errorf("扫描服务暂不可用")
		}
		job, err := scheduler.EnqueueScanByBindingID(r.Context(), d.user.ID, binding.LibraryID, body.BindingID, body.ExpectedRevision, body.OperationID, body.RulesRevision)
		if err != nil {
			return mediaScanError(err)
		}
		scheduler.Wake()
		return renderJSONStatus(w, scanSummary(job), http.StatusAccepted)
	})
}

func mediaScanGetHandler(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	job, err := ownerScan(r, d)
	if err != nil {
		return mediaScanError(err)
	}
	return renderJSON(w, r, scanSummary(job))
}

func ownerScan(r *http.Request, d *data) (*medialibrary.ScanJob, error) {
	vars := mux.Vars(r)
	if !validScanID(vars["scanId"]) || !validScanID(vars["id"]) {
		return nil, medialibrary.ErrNotFound
	}
	job, err := d.store.MediaLibrary.GetScan(r.Context(), d.user.ID, vars["scanId"])
	if err != nil {
		return nil, err
	}
	if job.LibraryID != vars["id"] {
		return nil, medialibrary.ErrNotFound
	}
	return job, nil
}

func mediaScanCancelHandler(scheduler scanScheduler) handleFunc {
	return mediaScanManagement(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if scheduler == nil || scheduler.Ready(r.Context()) != nil {
			return http.StatusServiceUnavailable, fmt.Errorf("扫描服务暂不可用")
		}
		var body mediaScanCancelBody
		if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedGeneration < 1 {
			return http.StatusBadRequest, fmt.Errorf("取消参数无效")
		}
		job, err := ownerScan(r, d)
		if err != nil {
			return mediaScanError(err)
		}
		if scheduler.Ready(r.Context()) != nil {
			return http.StatusServiceUnavailable, fmt.Errorf("扫描服务暂不可用")
		}
		job, err = scheduler.CancelScanWithOperation(r.Context(), d.user.ID, job.ID, body.ExpectedGeneration, body.OperationID)
		if err != nil {
			return mediaScanError(err)
		}
		return renderJSON(w, r, scanSummary(job))
	})
}

func mediaScanRetryHandler(scheduler scanScheduler) handleFunc {
	return mediaScanManagement(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if scheduler == nil || scheduler.Ready(r.Context()) != nil {
			return http.StatusServiceUnavailable, fmt.Errorf("扫描服务暂不可用")
		}
		var body mediaScanRetryBody
		if decodeMediaScanBody(w, r, &body) != nil || !validScanID(body.OperationID) || body.ExpectedGeneration < 1 {
			return http.StatusBadRequest, fmt.Errorf("重试参数无效")
		}
		job, err := ownerScan(r, d)
		if err != nil {
			return mediaScanError(err)
		}
		if job.RulesRevision != mediascan.LocalRulesRevision {
			return http.StatusConflict, fmt.Errorf("扫描规则已变化，请重新发起扫描")
		}
		binding, err := d.store.MediaLibrary.GetLibrarySource(r.Context(), d.user.ID, job.Binding.LibraryID, job.Binding.SourceID, job.Binding.RootLocator)
		if err != nil {
			return mediaScanError(err)
		}
		if _, err := mediascan.CheckBinding(r.Context(), d.store.MediaLibrary, d.user.ID, binding.Ref(), mediaScanResolver(d)); err != nil {
			return mediaScanError(err)
		}
		if scheduler.Ready(r.Context()) != nil {
			return http.StatusServiceUnavailable, fmt.Errorf("扫描服务暂不可用")
		}
		retry, err := scheduler.RetryScanWithOperation(r.Context(), d.user.ID, job.ID, body.ExpectedGeneration, body.OperationID)
		if err != nil {
			return mediaScanError(err)
		}
		scheduler.Wake()
		return renderJSONStatus(w, scanSummary(retry), http.StatusAccepted)
	})
}

func mediaScanError(err error) (int, error) {
	switch {
	case errors.Is(err, medialibrary.ErrNotFound), errors.Is(err, fs.ErrNotExist):
		return http.StatusNotFound, fmt.Errorf("扫描或来源不存在")
	case errors.Is(err, medialibrary.ErrForbidden), errors.Is(err, fs.ErrPermission):
		return http.StatusForbidden, fmt.Errorf("没有来源扫描权限")
	case errors.Is(err, medialibrary.ErrInvalid), errors.Is(err, fs.ErrInvalid):
		return http.StatusBadRequest, fmt.Errorf("扫描参数无效")
	case errors.Is(err, mediascan.ErrBindingRootSelection):
		return http.StatusBadRequest, fmt.Errorf("请选择具体磁盘或目录")
	case errors.Is(err, mediascan.ErrBindingUnverified), errors.Is(err, mediascan.ErrBindingChanged), errors.Is(err, medialibrary.ErrBindingProofUnsupported):
		return http.StatusConflict, fmt.Errorf("来源需要重新确认")
	case errors.Is(err, medialibrary.ErrRevisionConflict), errors.Is(err, medialibrary.ErrScanState), errors.Is(err, medialibrary.ErrScanActive), errors.Is(err, medialibrary.ErrArchived):
		return http.StatusConflict, fmt.Errorf("扫描状态已变化，请重新读取")
	case errors.Is(err, medialibrary.ErrCoordinatorUnavailable), errors.Is(err, fs.ErrClosed):
		return http.StatusServiceUnavailable, fmt.Errorf("扫描服务暂不可用")
	case errors.Is(err, files.ErrSubtitleScopeUnavailable):
		return http.StatusServiceUnavailable, fmt.Errorf("来源文件系统暂不支持安全扫描")
	default:
		return http.StatusInternalServerError, fmt.Errorf("扫描操作暂时失败")
	}
}
