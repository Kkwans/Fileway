package fbhttp

import (
	"errors"
	"fmt"
	"net/http"
	"strconv"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
	"github.com/Kkwans/nas-file-browser/backend/mediascan"
	"github.com/gorilla/mux"
)

type metadataRefreshJobBody struct {
	OperationID            string `json:"operationId"`
	Mode                   string `json:"mode"`
	ExpectedPolicyRevision int64  `json:"expectedPolicyRevision"`
}

type metadataRefreshJobActionBody struct {
	OperationID string `json:"operationId"`
	Generation  int64  `json:"generation"`
}

func mediaMetadataRefreshJobActionHandler(scheduler *mediascan.Scheduler, undo bool) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body metadataRefreshJobActionBody
		if decodeMediaScanBody(w, r, &body) != nil || body.Generation < 1 || scheduler == nil {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		library, err := itemLibrary(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		job, err := d.store.MediaLibrary.MetadataRefreshJob(r.Context(), d.user.ID, library.ID, mux.Vars(r)["jobId"])
		if err != nil {
			return catalogError(w, err)
		}
		if undo {
			result, err := scheduler.UndoMetadataRefreshJob(r.Context(), d.user.ID, body.OperationID, job, body.Generation)
			if err != nil {
				if errors.Is(err, medialibrary.ErrCoordinatorUnavailable) {
					return http.StatusServiceUnavailable, fmt.Errorf("后台资料刷新服务暂不可用")
				}
				return catalogError(w, err)
			}
			return renderJSON(w, r, result)
		}
		job, err = scheduler.RetryMetadataRefreshJobWithOperation(r.Context(), d.user.ID, body.OperationID, job, body.Generation)
		if err != nil {
			if errors.Is(err, medialibrary.ErrCoordinatorUnavailable) {
				return http.StatusServiceUnavailable, fmt.Errorf("后台资料刷新服务暂不可用")
			}
			return catalogError(w, err)
		}
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusAccepted)
		return renderMetadataRefreshJob(w, r, job)
	})
}

type metadataRefreshJobResponse struct {
	JobID          string `json:"jobId"`
	LibraryID      string `json:"libraryId"`
	Status         string `json:"status"`
	FailureCode    string `json:"failureCode,omitempty"`
	Generation     int64  `json:"generation"`
	Language       string `json:"language"`
	Region         string `json:"region"`
	Total          int64  `json:"total"`
	Refreshed      int64  `json:"refreshed"`
	AlreadyCurrent int64  `json:"alreadyCurrent"`
	Preserved      int64  `json:"preserved"`
	SourceChanged  int64  `json:"sourceChanged"`
	Failed         int64  `json:"failed"`
	Pending        int64  `json:"pending"`
	Undone         int64  `json:"undone"`
}

func renderMetadataRefreshJob(w http.ResponseWriter, r *http.Request, job medialibrary.MetadataRefreshJob) (int, error) {
	w.Header().Set("Cache-Control", "no-store")
	return renderJSON(w, r, metadataRefreshJobResponse{
		JobID: job.ID, LibraryID: job.LibraryID, Status: job.Status, FailureCode: job.FailureCode,
		Generation: job.Generation, Language: job.Language, Region: job.Region, Total: job.Total,
		Refreshed: job.Refreshed, AlreadyCurrent: job.AlreadyCurrent, Preserved: job.Preserved,
		SourceChanged: job.SourceChanged, Failed: job.Failed, Pending: job.Pending, Undone: job.Undone,
	})
}

func mediaMetadataRefreshJobsHandler(scheduler *mediascan.Scheduler) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body metadataRefreshJobBody
		if decodeMediaScanBody(w, r, &body) != nil || body.Mode != "refresh_unlocked" || body.ExpectedPolicyRevision < 1 {
			return catalogError(w, medialibrary.ErrInvalid)
		}
		library, err := itemLibrary(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		if scheduler == nil || !scheduler.RefreshMetadataAvailable(r.Context()) {
			return http.StatusServiceUnavailable, fmt.Errorf("后台资料刷新服务暂不可用")
		}
		job, err := scheduler.EnqueueMetadataRefreshJob(r.Context(), d.user.ID, body.OperationID, library.ID, body.ExpectedPolicyRevision)
		if err != nil {
			if errors.Is(err, medialibrary.ErrCoordinatorUnavailable) {
				return http.StatusServiceUnavailable, fmt.Errorf("后台资料刷新服务暂不可用")
			}
			return catalogError(w, err)
		}
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusAccepted)
		return renderMetadataRefreshJob(w, r, job)
	})
}

func mediaMetadataRefreshJobHandler(scheduler *mediascan.Scheduler) handleFunc {
	return providerConfig(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		library, err := itemLibrary(r, d)
		if err != nil {
			return catalogError(w, err)
		}
		job, err := d.store.MediaLibrary.MetadataRefreshJob(r.Context(), d.user.ID, library.ID, mux.Vars(r)["jobId"])
		if err != nil {
			return catalogError(w, err)
		}
		if r.Method == http.MethodDelete {
			generation, err := strconv.ParseInt(r.URL.Query().Get("generation"), 10, 64)
			if err != nil || generation < 1 || scheduler == nil {
				return catalogError(w, medialibrary.ErrInvalid)
			}
			if err := scheduler.CancelMetadataRefreshJob(r.Context(), d.user.ID, library.ID, job.ID, generation); err != nil {
				if errors.Is(err, medialibrary.ErrCoordinatorUnavailable) {
					return http.StatusServiceUnavailable, fmt.Errorf("后台资料刷新服务暂不可用")
				}
				return catalogError(w, err)
			}
			w.WriteHeader(http.StatusNoContent)
			return http.StatusNoContent, nil
		}
		return renderMetadataRefreshJob(w, r, job)
	})
}
