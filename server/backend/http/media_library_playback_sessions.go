package fbhttp

import (
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/gorilla/mux"
)

const (
	mediaLeaseLifetime     = 5 * time.Minute
	mediaPlaybackIdle      = 10 * time.Minute
	mediaRenewLead         = 2 * time.Minute
	mediaLeaseCapacity     = 4096
	mediaSessionsPerViewer = 32
	mediaReadersPerSession = 4
)

type mediaPlaybackLease struct {
	id, sessionID string
	access        mediaAccessContext
	held          *files.SubtitleResource
	info          os.FileInfo
	stamp         string

	// Mutable lifetime state is protected by mediaPlaybackLeases.mu.
	expires, idleDeadline time.Time
	generation            int64
	readers               int
	timer                 *time.Timer
	lastRenew             string
}

type mediaAdmission struct {
	SessionID       string `json:"sessionId"`
	LeaseID         string `json:"leaseId"`
	LeaseGeneration int64  `json:"leaseGeneration"`
	ExpiresAt       int64  `json:"expiresAt"`
	RenewAfter      int64  `json:"renewAfter"`
	ResumeMode      string `json:"resumeMode"`
	TimelineToken   string `json:"timelineToken"`
}

func (lease *mediaPlaybackLease) admission() mediaAdmission {
	return mediaAdmission{
		SessionID: lease.sessionID, LeaseID: lease.id, LeaseGeneration: lease.generation,
		ExpiresAt: lease.expires.UnixMilli(), RenewAfter: lease.expires.Add(-mediaRenewLead).UnixMilli(),
		ResumeMode: "same_session", TimelineToken: lease.sessionID,
	}
}

type mediaPlaybackLeases struct {
	mu              sync.Mutex
	items, sessions map[string]*mediaPlaybackLease
	now             func() time.Time
	closed          bool
}

func newMediaPlaybackLeases() *mediaPlaybackLeases {
	return &mediaPlaybackLeases{items: make(map[string]*mediaPlaybackLease), sessions: make(map[string]*mediaPlaybackLease), now: time.Now}
}

func (leases *mediaPlaybackLeases) create(access mediaAccessContext, held *files.SubtitleResource, info os.FileInfo, stamp string) (mediaAdmission, error) {
	id, err := newMediaOpaqueID()
	if err != nil {
		return mediaAdmission{}, err
	}
	sessionID, err := newMediaOpaqueID()
	if err != nil {
		return mediaAdmission{}, err
	}
	leases.mu.Lock()
	defer leases.mu.Unlock()
	if leases.closed || len(leases.items) >= mediaLeaseCapacity {
		return mediaAdmission{}, fs.ErrPermission
	}
	count := 0
	for _, item := range leases.items {
		if item.access.viewer == access.viewer {
			count++
		}
	}
	if count >= mediaSessionsPerViewer {
		return mediaAdmission{}, fs.ErrPermission
	}
	now := leases.now()
	lease := &mediaPlaybackLease{id: id, sessionID: sessionID, access: access, held: held, info: info, stamp: stamp,
		generation: 1, expires: now.Add(mediaLeaseLifetime), idleDeadline: now.Add(mediaPlaybackIdle)}
	leases.items[id], leases.sessions[sessionID] = lease, lease
	lease.timer = time.AfterFunc(mediaPlaybackIdle, func() { leases.expireIdle(lease) })
	return lease.admission(), nil
}

func (leases *mediaPlaybackLeases) expireIdle(lease *mediaPlaybackLease) {
	leases.mu.Lock()
	if leases.items[lease.id] != lease || lease.readers != 0 || leases.now().Before(lease.idleDeadline) {
		leases.mu.Unlock()
		return
	}
	leases.remove(lease)
	leases.mu.Unlock()
	_ = lease.held.Close()
}

// remove is called with mu held. The caller closes the file after unlocking.
func (leases *mediaPlaybackLeases) remove(lease *mediaPlaybackLease) {
	delete(leases.items, lease.id)
	delete(leases.sessions, lease.sessionID)
	lease.timer.Stop()
}

func (leases *mediaPlaybackLeases) revoke(lease *mediaPlaybackLease) {
	leases.mu.Lock()
	if leases.items[lease.id] != lease {
		leases.mu.Unlock()
		return
	}
	leases.remove(lease)
	leases.mu.Unlock()
	_ = lease.held.Close()
}

func (leases *mediaPlaybackLeases) close() {
	leases.mu.Lock()
	leases.closed = true
	closing := leases.items
	leases.items, leases.sessions = nil, nil
	leases.mu.Unlock()
	for _, lease := range closing {
		lease.timer.Stop()
		_ = lease.held.Close()
	}
}

func (leases *mediaPlaybackLeases) admit(id string, viewer uint, device string) (*mediaPlaybackLease, error) {
	leases.mu.Lock()
	defer leases.mu.Unlock()
	lease := leases.items[id]
	if lease == nil || lease.access.viewer != viewer || lease.access.session != device || !leases.now().Before(lease.expires) {
		return nil, fs.ErrPermission
	}
	if lease.readers >= mediaReadersPerSession {
		return nil, errMediaReadersBusy
	}
	lease.readers++
	lease.timer.Stop()
	return lease, nil
}

var errMediaReadersBusy = fmt.Errorf("播放读取请求过多，请稍后重试")

func (leases *mediaPlaybackLeases) release(lease *mediaPlaybackLease) {
	leases.mu.Lock()
	defer leases.mu.Unlock()
	lease.readers--
	if lease.readers == 0 && leases.items[lease.id] == lease {
		lease.idleDeadline = leases.now().Add(mediaPlaybackIdle)
		lease.timer.Reset(mediaPlaybackIdle)
	}
}

func (leases *mediaPlaybackLeases) active(lease *mediaPlaybackLease) bool {
	leases.mu.Lock()
	defer leases.mu.Unlock()
	return leases.items[lease.id] == lease
}

func (leases *mediaPlaybackLeases) session(id string, viewer uint, device string) *mediaPlaybackLease {
	leases.mu.Lock()
	defer leases.mu.Unlock()
	lease := leases.sessions[id]
	if lease == nil || lease.access.viewer != viewer || lease.access.session != device || lease.readers == 0 && !leases.now().Before(lease.idleDeadline) {
		return nil
	}
	return lease
}

func (leases *mediaPlaybackLeases) renew(lease *mediaPlaybackLease, operation string, expected int64) (mediaAdmission, int) {
	leases.mu.Lock()
	defer leases.mu.Unlock()
	now := leases.now()
	if leases.items[lease.id] != lease || lease.readers == 0 && !now.Before(lease.idleDeadline) {
		return mediaAdmission{}, http.StatusNotFound
	}
	if operation == lease.lastRenew && expected == lease.generation-1 {
		return lease.admission(), http.StatusOK
	}
	if expected != lease.generation || operation == lease.lastRenew {
		return mediaAdmission{}, http.StatusConflict
	}
	lease.generation++
	lease.lastRenew = operation
	lease.expires = now.Add(mediaLeaseLifetime)
	if lease.readers == 0 {
		lease.idleDeadline = now.Add(mediaPlaybackIdle)
		lease.timer.Reset(mediaPlaybackIdle)
	}
	return lease.admission(), http.StatusOK
}

func mediaPlaybackRenewHandler(leases *mediaPlaybackLeases) handleFunc {
	return mediaLibraryRead(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var body struct {
			OperationID        string `json:"operationId"`
			ExpectedGeneration int64  `json:"expectedLeaseGeneration"`
		}
		if err := deviceBody(w, r, &body); err != nil || body.ExpectedGeneration < 1 || body.OperationID == "" || len(body.OperationID) > 200 || !utf8.ValidString(body.OperationID) || strings.IndexFunc(body.OperationID, unicode.IsControl) >= 0 {
			return http.StatusBadRequest, fmt.Errorf("续期请求无效")
		}
		lease := leases.session(mux.Vars(r)["session"], d.user.ID, d.deviceSession)
		if lease == nil {
			return mediaAccessError(fs.ErrPermission)
		}
		if err := checkMediaPlayback(r.Context(), d, leases, lease); err != nil {
			return mediaAccessError(err)
		}
		admission, status := leases.renew(lease, body.OperationID, body.ExpectedGeneration)
		if status == http.StatusConflict {
			return status, fmt.Errorf("播放续期代次已变化")
		}
		if status != http.StatusOK {
			return mediaAccessError(fs.ErrPermission)
		}
		return renderJSON(w, r, admission)
	})
}

func mediaPlaybackCloseHandler(leases *mediaPlaybackLeases) handleFunc {
	return mediaLibraryRead(func(_ http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if lease := leases.session(mux.Vars(r)["session"], d.user.ID, d.deviceSession); lease != nil {
			leases.revoke(lease)
		}
		return http.StatusNoContent, nil
	})
}
