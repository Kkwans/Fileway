package fbhttp

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestHistorySelectedDeleteIsScopedAndRejectsEmpty(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "admin", Perm: users.Permissions{Admin: true}}, users.User{Username: "member"})
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")
	one, err := h.storage.History.Record(admin.ID, "file.rename", "/one", "", history.StatusSuccess)
	if err != nil {
		t.Fatal(err)
	}
	keep, err := h.storage.History.Record(admin.ID, "file.rename", "/keep", "", history.StatusSuccess)
	if err != nil {
		t.Fatal(err)
	}
	other, err := h.storage.History.Record(member.ID, "file.rename", "/other", "", history.StatusSuccess)
	if err != nil {
		t.Fatal(err)
	}
	body := strings.NewReader(fmt.Sprintf(`{"ids":[%q,%q,%q]}`, one.ID, one.ID, other.ID))
	response := h.request(t, admin.ID, historyDeleteSelectedHandler, http.MethodPost, "/history/delete", body, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	var result historyDeleteAllResponse
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil || result.Deleted != 1 {
		t.Fatalf("result=%+v error=%v", result, err)
	}
	for _, bad := range []string{`{"ids":[]}`, `{"ids":[""]}`, `{"ids":["missing"],"all":true}`, `{"ids":["missing"]} {}`} {
		response = h.request(t, admin.ID, historyDeleteSelectedHandler, http.MethodPost, "/history/delete", strings.NewReader(bad), nil)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("invalid request status=%d body=%s", response.Code, response.Body.String())
		}
	}
	rows, err := h.storage.History.List(admin.ID, 0)
	if err != nil || len(rows) != 1 || rows[0].ID != keep.ID {
		t.Fatalf("unselected own rows changed: %v %v", rows, err)
	}
	rows, err = h.storage.History.List(member.ID, 0)
	if err != nil || len(rows) != 1 || rows[0].ID != other.ID {
		t.Fatalf("other user changed: %v %v", rows, err)
	}
}

func TestHistoryHTTPIsPrivateEvenForAdmin(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true}},
		users.User{Username: "member"},
	)
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")
	if _, err := h.storage.History.Record(admin.ID, "file.rename", "/admin.txt", "", history.StatusSuccess); err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.History.Record(member.ID, "file.rename", "/member.txt", "", history.StatusSuccess); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, admin.ID, historyListHandler, http.MethodGet, "/history", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("history status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "member.txt") || strings.Contains(response.Body.String(), "userId") {
		t.Fatalf("private history leaked: %s", response.Body.String())
	}
	var listed historyListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	if listed.Total != 1 || len(listed.Items) != 1 || listed.Items[0].Target != "/admin.txt" {
		t.Fatalf("admin private history = %#v", listed)
	}

	response = h.request(t, admin.ID, historyListHandler, http.MethodGet, "/history?limit=invalid", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid limit status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestHistoryHTTPFiltersAndPaginates(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "member"})
	member := trashHTTPUserByName(t, h, "member")
	first, err := h.storage.History.Record(member.ID, "file.rename", "/docs/report.md", "/docs/draft.md", history.StatusSuccess)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.History.Record(member.ID, "file.delete", "/tmp/failed.txt", "", history.StatusFailed); err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.History.Record(member.ID, "analysis.storage", "/media", "", history.StatusSubmitted); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, member.ID, historyListHandler, http.MethodGet, "/history?limit=1", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("first page status = %d body=%s", response.Code, response.Body.String())
	}
	var firstPage historyListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &firstPage); err != nil {
		t.Fatal(err)
	}
	if firstPage.Total != 3 || len(firstPage.Items) != 1 || firstPage.NextCursor == "" {
		t.Fatalf("first page = %#v", firstPage)
	}
	response = h.request(t, member.ID, historyListHandler, http.MethodGet, "/history?limit=1&cursor="+firstPage.NextCursor, nil, nil)
	var secondPage historyListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &secondPage); err != nil {
		t.Fatal(err)
	}
	if len(secondPage.Items) != 1 || secondPage.Items[0].ID == firstPage.Items[0].ID {
		t.Fatalf("second page = %#v", secondPage)
	}

	filterURL := fmt.Sprintf("/history?text=report&action=file.rename&status=success&from=%d&to=%d", first.CreatedAt, first.CreatedAt)
	response = h.request(t, member.ID, historyListHandler, http.MethodGet, filterURL, nil, nil)
	var filtered historyListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &filtered); err != nil {
		t.Fatal(err)
	}
	if filtered.Total != 1 || len(filtered.Items) != 1 || filtered.Items[0].ID != first.ID {
		t.Fatalf("filtered history = %#v", filtered)
	}

	for _, invalidURL := range []string{"/history?status=unknown", "/history?cursor=invalid", "/history?limit=101"} {
		response = h.request(t, member.ID, historyListHandler, http.MethodGet, invalidURL, nil, nil)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("invalid query %s status = %d", invalidURL, response.Code)
		}
	}
}

func TestHistoryHTTPDeleteAllIsUserScoped(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "owner"},
		users.User{Username: "other"},
	)
	owner := trashHTTPUserByName(t, h, "owner")
	other := trashHTTPUserByName(t, h, "other")
	if _, err := h.storage.History.Record(owner.ID, "file.rename", "/owner", "", history.StatusSuccess); err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.History.Record(other.ID, "file.rename", "/other", "", history.StatusSuccess); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, historyDeleteAllHandler, http.MethodDelete, "/history", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("delete history status = %d body=%s", response.Code, response.Body.String())
	}
	var deleted historyDeleteAllResponse
	if err := json.Unmarshal(response.Body.Bytes(), &deleted); err != nil {
		t.Fatal(err)
	}
	if deleted.Deleted != 1 {
		t.Fatalf("deleted = %#v", deleted)
	}
	ownerEntries, err := h.storage.History.List(owner.ID, 10)
	if err != nil || len(ownerEntries) != 0 {
		t.Fatalf("owner history = %#v err=%v", ownerEntries, err)
	}
	otherEntries, err := h.storage.History.List(other.ID, 10)
	if err != nil || len(otherEntries) != 1 {
		t.Fatalf("other history = %#v err=%v", otherEntries, err)
	}
}

func TestTrashOperationsAreRecordedInHistory(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "member",
		Perm: users.Permissions{
			Create: true, Delete: true, Modify: true, Rename: true, Download: true,
		},
	})
	member := trashHTTPUserByName(t, h, "member")
	moveTrashFixture(t, h, member, "/history.txt")
	items, err := h.storage.Trash.List(member.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 {
		t.Fatalf("trash items = %#v", items)
	}
	response := h.request(t, member.ID, trashRestoreHandler, http.MethodPost, "/trash/"+items[0].ID+"/restore", bytes.NewBufferString(`{"conflict":"fail"}`), map[string]string{"id": items[0].ID})
	if response.Code != http.StatusOK {
		t.Fatalf("restore status = %d body=%s", response.Code, response.Body.String())
	}

	entries, err := h.storage.History.List(member.ID, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 || entries[0].Action != "trash.restore" || entries[1].Action != "trash.move" {
		t.Fatalf("trash history = %#v", entries)
	}
	if entries[0].Status != history.StatusSuccess || entries[0].Target != "/history.txt" {
		t.Fatalf("restore history = %#v", entries[0])
	}
}
