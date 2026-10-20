package transport

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const maxUploadChunk = 64 << 20

type UploadOptions struct {
	Protocol   string            `json:"protocol"`
	Size       int64             `json:"size"`
	TransferID string            `json:"transferId"`
	Overwrite  bool              `json:"overwrite"`
	Resume     bool              `json:"resume"`
	Metadata   map[string]string `json:"metadata"`
}

type uploadCapability struct {
	mu       sync.Mutex
	options  UploadOptions
	endpoint string
	created  bool
	finished bool
	accepted atomic.Int64
}

type UploadStatistics struct {
	SentBytes      int64  `json:"sentBytes"`
	AcceptedOffset int64  `json:"acceptedOffset"`
	CurrentSent    int64  `json:"currentSent"`
	CurrentSize    int64  `json:"currentSize"`
	RequestID      uint64 `json:"requestId"`
	ElapsedMillis  int64  `json:"elapsedMillis"`
	Active         bool   `json:"active"`
}

var uploadMetadataHeaders = map[string]bool{
	"X-Upload-Batch-ID": true, "X-Upload-Batch-Name": true,
	"X-Upload-Batch-Items": true, "X-Upload-Batch-Bytes": true, "X-Upload-Folder": true,
}

// UploadLease grants writes to one original resource and account. Playback and
// preview capabilities remain read-only; upload bytes never enter JSON/JNI.
func (b *Broker) UploadLease(id, path, wire string, options UploadOptions) (string, error) {
	if (options.Protocol != "resources" && options.Protocol != "tus") || options.Size < -1 ||
		(options.Protocol == "tus" && options.Size < 0) || (options.Protocol == "resources" && options.Resume) {
		return "", errors.New("invalid upload protocol or size")
	}
	if len(options.TransferID) == 0 || len(options.TransferID) > 220 || strings.IndexFunc(options.TransferID,
		func(r rune) bool {
			return !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' || r == '.')
		}) >= 0 {
		return "", errors.New("invalid upload transfer ID")
	}
	headers := make(map[string]string)
	for name, value := range options.Metadata {
		if !uploadMetadataHeaders[name] || len(value) > 1024 || strings.ContainsAny(value, "\r\n") {
			return "", errors.New("invalid upload metadata")
		}
		headers[name] = value
	}
	options.Metadata = headers
	raw, err := RawEndpoint(path, wire)
	if err != nil {
		return "", err
	}
	resource := strings.TrimPrefix(raw, "/api/raw")
	if resource == "/" || strings.HasSuffix(resource, "/") {
		return "", errors.New("upload target must be a file")
	}
	endpoint := "/api/" + options.Protocol + resource + "?override=" + strconv.FormatBool(options.Overwrite)
	if options.Resume {
		endpoint = "/api/tus" + resource + "?transfer=" + url.QueryEscape(options.TransferID)
	}
	u := &uploadCapability{options: options, endpoint: endpoint, created: options.Resume}
	u.accepted.Store(-1)
	return b.lease(id, endpoint, false, u)
}

func uploadNumber(value string) (int64, error) {
	if value == "" || strings.IndexFunc(value, func(r rune) bool { return r < '0' || r > '9' }) >= 0 {
		return 0, errors.New("invalid upload length or offset")
	}
	return strconv.ParseInt(value, 10, 64)
}

// The NAS returns a service-base-qualified Location. It may only transition to
// the exact original file and this transfer ID; never follow arbitrary URLs.
func uploadLocation(base *url.URL, current, location, transfer string) (string, error) {
	from, err := Endpoint(base, current)
	if err != nil {
		return "", err
	}
	ref, err := url.Parse(location)
	if err != nil || location == "" || ref.User != nil || ref.Fragment != "" || ref.Opaque != "" {
		return "", errors.New("invalid upload location")
	}
	target := from.ResolveReference(ref)
	query, err := url.ParseQuery(target.RawQuery)
	if err != nil || target.Scheme != base.Scheme || target.Host != base.Host ||
		target.Path != from.Path || target.User != nil || len(query) > 1 ||
		(len(query) == 1 && (len(query["transfer"]) != 1 || query.Get("transfer") != transfer)) {
		return "", errors.New("upload location changed source or target")
	}
	// Use the pinned wire path, rather than re-encoding decoded legacy bytes.
	return strings.Split(current, "?")[0] + "?transfer=" + url.QueryEscape(transfer), nil
}

type uploadReader struct {
	io.ReadCloser
	stats *leaseTelemetry
	id    uint64
}

func (r uploadReader) Read(p []byte) (int, error) {
	n, err := r.ReadCloser.Read(p)
	r.stats.add(int64(n), 0, int64(n), r.id)
	return n, err
}

func (b *Broker) serveUpload(w http.ResponseWriter, r *http.Request, l *Lease) {
	u := l.upload
	// HEAD and writes serialize on a capability. Two uploaders cannot append
	// overlapping chunks while observing the same offset.
	if !u.mu.TryLock() {
		http.Error(w, "upload request already running", http.StatusConflict)
		return
	}
	defer u.mu.Unlock()
	method := r.Method
	if override := r.Header.Get("X-HTTP-Method-Override"); override != "" {
		if method != http.MethodPost || override != http.MethodPatch {
			http.Error(w, "unsupported upload method override", http.StatusMethodNotAllowed)
			return
		}
		method = http.MethodPatch
	}
	if u.options.Protocol == "resources" && method != http.MethodPost || u.options.Protocol == "tus" &&
		method != http.MethodHead && method != http.MethodPost && method != http.MethodPatch && method != http.MethodDelete {
		w.Header().Set("Allow", map[bool]string{true: "POST", false: "HEAD, POST, PATCH, DELETE"}[u.options.Protocol == "resources"])
		http.Error(w, "unsupported upload method", http.StatusMethodNotAllowed)
		return
	}
	if u.finished || method == http.MethodPost && u.created ||
		u.options.Protocol == "tus" && (method == http.MethodPatch || method == http.MethodDelete) && !u.created {
		http.Error(w, "upload state changed", http.StatusConflict)
		return
	}
	limit := u.options.Size
	requestedOffset := int64(-1)
	if u.options.Protocol == "tus" {
		if version := r.Header.Get("Tus-Resumable"); version != "" && version != "1.0.0" {
			http.Error(w, "unsupported upload version", http.StatusPreconditionFailed)
			return
		}
		limit = 0
		if method == http.MethodPost {
			size, err := uploadNumber(r.Header.Get("Upload-Length"))
			if err != nil || size != u.options.Size || r.ContentLength != 0 {
				http.Error(w, "invalid upload creation length", http.StatusBadRequest)
				return
			}
		} else if method == http.MethodPatch {
			offset, err := uploadNumber(r.Header.Get("Upload-Offset"))
			if err != nil || offset > u.options.Size || r.Header.Get("Content-Type") != "application/offset+octet-stream" {
				http.Error(w, "invalid upload chunk", http.StatusBadRequest)
				return
			}
			requestedOffset = offset
			limit = min(maxUploadChunk, u.options.Size-offset)
		}
	}
	if limit >= 0 && r.ContentLength > limit || u.options.Protocol == "resources" && u.options.Size >= 0 && r.ContentLength != u.options.Size {
		http.Error(w, "upload body exceeds granted length", http.StatusRequestEntityTooLarge)
		return
	}
	ctx, done := combined(r.Context(), l.ctx)
	// Interrupt a stalled incoming body too, not merely the destination's
	// socket. This lease request owns its loopback connection until release.
	w.Header().Set("Connection", "close")
	stopRead := context.AfterFunc(ctx, func() { _ = http.NewResponseController(w).SetReadDeadline(time.Now()) })
	defer func() { stopRead(); done() }()
	target, err := Endpoint(l.session.base, u.endpoint)
	if err != nil {
		http.Error(w, "upload source unavailable", http.StatusGone)
		return
	}
	body := r.Body
	if limit >= 0 {
		body = http.MaxBytesReader(w, r.Body, limit)
	}
	var requestID uint64
	if method == http.MethodPost || method == http.MethodPatch {
		requestID = l.telemetry.begin()
		l.telemetry.total(requestID, r.ContentLength)
		defer l.telemetry.finish(requestID)
		body = uploadReader{ReadCloser: body, stats: &l.telemetry, id: requestID}
	}
	req, err := http.NewRequestWithContext(ctx, method, target.String(), body)
	if err != nil {
		http.Error(w, "invalid upload request", http.StatusBadRequest)
		return
	}
	req.ContentLength = r.ContentLength
	if r.ContentLength == 0 {
		req.Body = http.NoBody
	}
	for _, name := range []string{"Content-Type", "Tus-Resumable", "Upload-Length", "Upload-Offset", "Upload-Metadata"} {
		if value := r.Header.Get(name); value != "" {
			req.Header.Set(name, value)
		}
	}
	for name, value := range u.options.Metadata {
		req.Header.Set(name, value)
	}
	req.Header.Set("X-Transfer-ID", u.options.TransferID)
	previous := l.session.currentToken()
	if previous != "" {
		req.Header.Set("X-Auth", previous)
	}
	res, err := l.session.client.Do(req)
	if err != nil {
		http.Error(w, "upload interrupted; inspect remote offset before retry", http.StatusBadGateway)
		return
	}
	defer res.Body.Close()
	l.session.afterHeaders(ctx, res, previous)
	if res.Header.Get("X-Fileway-Session-Health") == "expired" || res.Header.Get("X-Fileway-Session-Expired") == "true" {
		w.Header().Set("X-Fileway-Session-Expired", "true")
	}

	if u.options.Protocol == "tus" && method == http.MethodPost && res.StatusCode == http.StatusCreated {
		next, err := uploadLocation(l.session.base, u.endpoint, res.Header.Get("Location"), u.options.TransferID)
		if err != nil {
			http.Error(w, "upload accepted with invalid location; inspect destination", http.StatusBadGateway)
			return
		}
		u.endpoint = next
		u.created = true
		w.Header().Set("Location", "http://"+b.listener.Addr().String()+r.URL.Path)
	}
	if u.options.Protocol == "resources" && res.StatusCode == http.StatusOK {
		sent := l.telemetry.snapshot().CurrentReadBytes
		if u.options.Size >= 0 && sent != u.options.Size {
			http.Error(w, "upload acknowledged before complete body; inspect destination", http.StatusBadGateway)
			return
		}
		u.accepted.Store(sent)
		u.finished = true
	}
	if u.options.Protocol == "tus" && (method == http.MethodPatch && res.StatusCode == http.StatusNoContent ||
		method == http.MethodHead && res.StatusCode == http.StatusOK) {
		offset, err := uploadNumber(res.Header.Get("Upload-Offset"))
		valid := err == nil && offset <= u.options.Size
		if method == http.MethodHead {
			length, err := uploadNumber(res.Header.Get("Upload-Length"))
			valid = valid && err == nil && length == u.options.Size
		} else {
			valid = valid && offset == requestedOffset+l.telemetry.snapshot().CurrentReadBytes
		}
		if !valid {
			http.Error(w, "invalid upload acknowledgement; inspect remote state", http.StatusBadGateway)
			return
		}
		u.accepted.Store(offset)
		if method == http.MethodPatch && offset == u.options.Size {
			u.finished = true
		}
	}
	if method == http.MethodDelete && res.StatusCode == http.StatusNoContent {
		u.finished = true
	}
	for _, name := range []string{"Content-Type", "Tus-Resumable", "Upload-Length", "Upload-Offset", "Upload-Expires", "ETag"} {
		if value := res.Header.Get(name); value != "" {
			w.Header().Set(name, value)
		}
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(res.StatusCode)
	if method != http.MethodHead {
		_, _ = io.Copy(w, io.LimitReader(res.Body, maxAPIResponse))
	}
}

func (b *Broker) UploadStats(session, value string) (UploadStatistics, error) {
	key, ok := strings.CutPrefix(value, "http://"+b.listener.Addr().String()+"/stream/")
	if !ok {
		return UploadStatistics{}, errors.New("unknown upload")
	}
	b.mu.RLock()
	l := b.leases[key]
	s := b.sessions[session]
	valid := l != nil && s != nil && l.session == s && l.upload != nil && l.ctx.Err() == nil
	b.mu.RUnlock()
	if !valid {
		return UploadStatistics{}, errors.New("upload closed or belongs to another session")
	}
	v := l.telemetry.snapshot()
	// Statistics queries must remain responsive while a large body is in flight.
	return UploadStatistics{v.UpstreamBytes, l.upload.accepted.Load(), v.CurrentReadBytes, v.CurrentReadTotal, v.RequestID, v.ElapsedMillis, v.Active}, nil
}
