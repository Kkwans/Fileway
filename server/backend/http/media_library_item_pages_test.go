package fbhttp

import (
	"context"
	"testing"
)

func TestVisibleMetadataPageFiltersBeforeCursor(t *testing.T) {
	type entry struct {
		cursor  string
		allowed bool
	}
	all := []entry{{"001", false}, {"002", true}, {"003", false}, {"004", true}, {"005", false}, {"006", true}}
	fetch := func(_ context.Context, after string, limit int) ([]entry, string, int64, error) {
		var items []entry
		for _, value := range all {
			if value.cursor > after {
				items = append(items, value)
			}
		}
		if len(items) > limit {
			return items[:limit], items[limit-1].cursor, 7, nil
		}
		return items, "", 7, nil
	}
	allowed := func(value entry) (bool, error) { return value.allowed, nil }
	cursor := func(value entry) string { return value.cursor }
	first, err := visibleMetadataPageFor(t.Context(), 7, 2, "", fetch, allowed, cursor)
	if err != nil || len(first.Items) != 2 || first.Items[0].cursor != "002" || first.Items[1].cursor != "004" || first.NextCursor != "004" {
		t.Fatalf("first page: %+v %v", first, err)
	}
	last, err := visibleMetadataPageFor(t.Context(), 7, 2, first.NextCursor, fetch, allowed, cursor)
	if err != nil || len(last.Items) != 1 || last.Items[0].cursor != "006" || last.NextCursor != "" {
		t.Fatalf("last page: %+v %v", last, err)
	}
	_, err = visibleMetadataPageFor(t.Context(), 8, 2, "", fetch, allowed, cursor)
	if err == nil {
		t.Fatalf("revision mismatch accepted: %+v", last)
	}
}
