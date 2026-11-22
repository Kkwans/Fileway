package fbhttp

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/medialibrary"
)

func TestCatalogRevalidationBoundsWorkAndWaitsForEveryCheck(t *testing.T) {
	started := make(chan struct{}, 8)
	gate := make(chan struct{})
	var release sync.Once
	defer release.Do(func() { close(gate) })
	var running, maximum, completed atomic.Int32
	checks := make([]func(context.Context) error, 8)
	for i := range checks {
		checks[i] = func(ctx context.Context) error {
			current := running.Add(1)
			for old := maximum.Load(); current > old && !maximum.CompareAndSwap(old, current); old = maximum.Load() {
			}
			started <- struct{}{}
			select {
			case <-gate:
			case <-ctx.Done():
				return ctx.Err()
			}
			running.Add(-1)
			completed.Add(1)
			return nil
		}
	}
	done := make(chan error, 1)
	go func() { done <- runCatalogRevalidations(t.Context(), checks) }()
	for i := 0; i < 4; i++ {
		select {
		case <-started:
		case <-time.After(5 * time.Second):
			t.Fatal("independent checks did not run concurrently")
		}
	}
	release.Do(func() { close(gate) })
	if err := <-done; err != nil || completed.Load() != 8 || maximum.Load() > 4 {
		t.Fatalf("revalidation did not complete safely: done=%d max=%d err=%v", completed.Load(), maximum.Load(), err)
	}
}

func TestCatalogRevalidationCancellationStopsWaitingChecks(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	started := make(chan struct{}, 8)
	checks := make([]func(context.Context) error, 8)
	for i := range checks {
		checks[i] = func(ctx context.Context) error { started <- struct{}{}; <-ctx.Done(); return ctx.Err() }
	}
	done := make(chan error, 1)
	go func() { done <- runCatalogRevalidations(ctx, checks) }()
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("check never started")
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("cancellation lost: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("canceled revalidation did not release workers")
	}
}

func TestCatalogRevalidationFailureCancelsOtherChecks(t *testing.T) {
	started := make(chan struct{})
	stopped := make(chan struct{})
	denied := errors.New("source changed")
	checks := []func(context.Context) error{
		func(ctx context.Context) error { close(started); <-ctx.Done(); close(stopped); return ctx.Err() },
		func(context.Context) error { <-started; return denied },
	}
	if err := runCatalogRevalidations(t.Context(), checks); !errors.Is(err, denied) {
		t.Fatalf("source failure did not prevent publication: %v", err)
	}
	select {
	case <-stopped:
	default:
		t.Fatal("other source check was not canceled and joined")
	}
}

func TestCatalogRevalidationRejectsRevokedSourceBeforePublication(t *testing.T) {
	f := newMediaScanHTTPFixture(t)
	catalogFixtureWrite(t, f, "film.mkv", "owned video")
	catalogFixtureWrite(t, f, "film.nfo", `<movie><title>Owned film</title></movie>`)
	library, source := f.libraryAndSource(t)
	scanFixtureLibrary(t, f, library, source, "revalidation-revoke")
	settings, err := f.h.storage.Settings.Get()
	if err != nil {
		t.Fatal(err)
	}
	owner, err := f.h.storage.Users.Get(f.h.server.Root, f.owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	d := &data{store: f.h.storage, server: f.h.server, settings: settings, user: owner}
	snapshot, err := beginCatalogSnapshot(t.Context(), d, "")
	if err != nil {
		t.Fatal(err)
	}
	view := newCatalogView(d)
	page, err := view.browse(t.Context(), snapshot, browseRequest{Sort: "newest", Limit: 20}, "")
	if err != nil || len(page.Items) != 1 || len(view.checks) == 0 || len(view.metadataChecks) == 0 {
		t.Fatalf("fixture did not project both sources: cards=%d sources=%d metadata=%d err=%v", len(page.Items), len(view.checks), len(view.metadataChecks), err)
	}
	owner.Perm.Download = false
	if err = f.h.storage.Users.Update(owner, "Perm"); err != nil {
		t.Fatal(err)
	}
	if err = view.revalidateSources(t.Context()); !errors.Is(err, medialibrary.ErrRevisionConflict) {
		t.Fatalf("revoked source reached publication: %v", err)
	}
}
