package fbhttp

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/events"
	"github.com/Kkwans/nas-file-browser/backend/history"
)

const (
	defaultHistoryPageSize = 30
	maxHistoryPageSize     = 100
)

type historyListResponse struct {
	Items      []*history.Entry `json:"items"`
	NextCursor string           `json:"nextCursor,omitempty"`
	Total      int              `json:"total"`
}

type historyCursor struct {
	CreatedAt int64  `json:"createdAt"`
	ID        string `json:"id"`
}

type historyDeleteAllResponse struct {
	Deleted int `json:"deleted"`
}

var historyListHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	query := r.URL.Query()
	limit := defaultHistoryPageSize
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed <= 0 || parsed > maxHistoryPageSize {
			return http.StatusBadRequest, fmt.Errorf("limit 必须在 1 到 %d 之间", maxHistoryPageSize)
		}
		limit = parsed
	}
	status := history.Status(strings.TrimSpace(query.Get("status")))
	if status != "" && status != history.StatusSuccess && status != history.StatusFailed && status != history.StatusSubmitted {
		return http.StatusBadRequest, fmt.Errorf("未知历史状态 %q", status)
	}
	from, err := parseTaskTimestamp(query.Get("from"))
	if err != nil || from < 0 {
		return http.StatusBadRequest, fmt.Errorf("from 无效")
	}
	to, err := parseTaskTimestamp(query.Get("to"))
	if err != nil || to < 0 || from > 0 && to > 0 && from > to {
		return http.StatusBadRequest, fmt.Errorf("to 无效")
	}
	var cursor *historyCursor
	if raw := strings.TrimSpace(query.Get("cursor")); raw != "" {
		cursor, err = decodeHistoryCursor(raw)
		if err != nil {
			return http.StatusBadRequest, fmt.Errorf("cursor 无效")
		}
	}
	entries, err := d.store.History.List(d.user.ID, history.MaxEntriesPerUser)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	entries = filterHistoryEntries(entries, strings.TrimSpace(query.Get("text")), strings.TrimSpace(query.Get("action")), status, from, to)
	total := len(entries)
	if cursor != nil {
		entries = historyAfterCursor(entries, *cursor)
	}
	response := historyListResponse{Total: total, Items: entries}
	if len(entries) > limit {
		response.Items = entries[:limit]
		response.NextCursor = encodeHistoryCursor(response.Items[len(response.Items)-1])
	}
	if response.Items == nil {
		response.Items = []*history.Entry{}
	}
	return renderJSON(w, r, response)
})

var historyDeleteAllHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.store.History == nil {
		return http.StatusServiceUnavailable, fmt.Errorf("操作历史服务不可用")
	}
	deleted, err := d.store.History.DeleteAll(d.user.ID)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, historyDeleteAllResponse{Deleted: deleted})
})

var historyDeleteSelectedHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.store.History == nil {
		return http.StatusServiceUnavailable, fmt.Errorf("操作历史服务不可用")
	}
	var request struct {
		IDs []string `json:"ids"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil {
		return http.StatusBadRequest, fmt.Errorf("删除条目无效: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return http.StatusBadRequest, fmt.Errorf("删除请求只能包含一个对象")
	}
	if len(request.IDs) == 0 || len(request.IDs) > history.MaxEntriesPerUser {
		return http.StatusBadRequest, fmt.Errorf("请选择 1 到 %d 条操作记录", history.MaxEntriesPerUser)
	}
	for _, id := range request.IDs {
		if strings.TrimSpace(id) == "" || len(id) > 200 {
			return http.StatusBadRequest, fmt.Errorf("记录标识无效")
		}
	}
	deleted, err := d.store.History.DeleteSelected(d.user.ID, request.IDs)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, historyDeleteAllResponse{Deleted: deleted})
})

func filterHistoryEntries(entries []*history.Entry, text, action string, status history.Status, from, to int64) []*history.Entry {
	needle := strings.ToLower(strings.TrimSpace(text))
	filtered := make([]*history.Entry, 0, len(entries))
	for _, entry := range entries {
		if action != "" && entry.Action != action || status != "" && entry.Status != status {
			continue
		}
		if from > 0 && entry.CreatedAt < from || to > 0 && entry.CreatedAt > to {
			continue
		}
		if needle != "" {
			haystack := strings.ToLower(strings.Join([]string{entry.Action, entry.Target, entry.Detail}, "\n"))
			if !strings.Contains(haystack, needle) {
				continue
			}
		}
		filtered = append(filtered, entry)
	}
	return filtered
}

func historyAfterCursor(entries []*history.Entry, cursor historyCursor) []*history.Entry {
	for index, entry := range entries {
		if entry.CreatedAt < cursor.CreatedAt || entry.CreatedAt == cursor.CreatedAt && entry.ID < cursor.ID {
			return entries[index:]
		}
	}
	return []*history.Entry{}
}

func encodeHistoryCursor(entry *history.Entry) string {
	encoded, _ := json.Marshal(historyCursor{CreatedAt: entry.CreatedAt, ID: entry.ID})
	return base64.RawURLEncoding.EncodeToString(encoded)
}

func decodeHistoryCursor(raw string) (*historyCursor, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return nil, err
	}
	var cursor historyCursor
	if err := json.Unmarshal(decoded, &cursor); err != nil || cursor.CreatedAt <= 0 || cursor.ID == "" {
		return nil, fmt.Errorf("invalid cursor")
	}
	return &cursor, nil
}

// recordHistory is deliberately best-effort: a completed filesystem
// operation must not be reported as failed solely because its activity entry
// could not be persisted.
func recordHistory(d *data, action, target, detail string, status history.Status) {
	if d.store.History == nil || d.user == nil {
		return
	}
	entry, err := d.store.History.Record(d.user.ID, action, target, detail, status)
	if err != nil {
		log.Printf("WARNING: failed to record %s history for user %d: %v", action, d.user.ID, err)
		return
	}
	events.Default.PublishForUser(entry.UserID, "history.created", entry)
}
