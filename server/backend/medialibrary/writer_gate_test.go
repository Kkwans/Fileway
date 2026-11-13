package medialibrary

import (
	"context"
	"database/sql"
	"errors"
	"testing"
	"time"
)

func TestWriterGateCancellationDoesNotBlockReaders(t *testing.T) {
	store := openTestStore(t)
	library := createLibrary(t, store, 7, "Before")
	entered, release, completed := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	t.Cleanup(func() {
		select {
		case <-release:
		default:
			close(release)
		}
	})
	go func() {
		completed <- store.WithTx(context.Background(), func(tx *sql.Tx) error {
			if _, err := tx.Exec("UPDATE library SET name='Uncommitted' WHERE id=?", library.ID); err != nil {
				return err
			}
			close(entered)
			<-release
			return nil
		})
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("writer did not enter")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer cancel()
	read, err := store.GetLibrary(ctx, 7, library.ID)
	if err != nil || read.Name != "Before" {
		close(release)
		t.Fatal(read, err)
	}
	err = store.WithTx(ctx, func(*sql.Tx) error { t.Error("canceled waiter entered transaction"); return nil })
	if !errors.Is(err, context.DeadlineExceeded) {
		close(release)
		t.Fatal(err)
	}
	close(release)
	if err := <-completed; err != nil {
		t.Fatal(err)
	}
	if err := store.WithTx(t.Context(), func(*sql.Tx) error { return nil }); err != nil {
		t.Fatal(err)
	}
}

func TestWriterGateReleasesAndRollsBackAfterPanic(t *testing.T) {
	store := openTestStore(t)
	func() {
		defer func() {
			if recover() == nil {
				t.Error("expected panic")
			}
		}()
		_ = store.WithTx(t.Context(), func(tx *sql.Tx) error {
			if err := addLibrary(t.Context(), tx, "rolled-back"); err != nil {
				t.Fatal(err)
			}
			panic("injected")
		})
	}()
	ctx, cancel := context.WithTimeout(t.Context(), 2*time.Second)
	defer cancel()
	if err := store.WithTx(ctx, func(tx *sql.Tx) error { return addLibrary(ctx, tx, "next") }); err != nil {
		t.Fatal(err)
	}
	if got := tableCount(t, store, "library"); got != 1 {
		t.Fatalf("library count = %d", got)
	}
}
