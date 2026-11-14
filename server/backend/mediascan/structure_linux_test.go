//go:build linux

package mediascan

import (
	"database/sql"
	"testing"
)

func TestDirectorySidecarCaseVariants(t *testing.T) {
	t.Run("different evidence remains unmatched", func(t *testing.T) {
		f := newPersistentFixture(t)
		f.write(t, "Show/tvshow.nfo", `<tvshow><title>One</title></tvshow>`)
		f.write(t, "Show/TVSHOW.NFO", `<tvshow><title>Other</title></tvshow>`)
		f.write(t, "Show/Show.S01E01.mkv", "video")
		if _, err := Run(t.Context(), 7, f.store, f.coord, f.claim(t), f.ownerResolve, Options{}); err != nil {
			t.Fatal(err)
		}
		if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
			var series, videos, conflicts int
			if err := tx.QueryRow(`SELECT count(*) FROM library_item WHERE kind='series'`).Scan(&series); err != nil {
				return err
			}
			if err := tx.QueryRow(`SELECT count(*) FROM library_item WHERE kind='video'`).Scan(&videos); err != nil {
				return err
			}
			if err := tx.QueryRow(`SELECT count(*) FROM directory_context WHERE tv_status='conflict'`).Scan(&conflicts); err != nil {
				return err
			}
			if series != 0 || videos != 1 || conflicts != 1 {
				t.Fatalf("series=%d videos=%d conflicts=%d", series, videos, conflicts)
			}
			return nil
		}); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("identical evidence keeps series", func(t *testing.T) {
		f := newPersistentFixture(t)
		nfo := `<tvshow><title>One</title></tvshow>`
		f.write(t, "Show/tvshow.nfo", nfo)
		f.write(t, "Show/Show.S01E01.mkv", "video")
		if _, err := Run(t.Context(), 7, f.store, f.coord, f.claim(t), f.ownerResolve, Options{}); err != nil {
			t.Fatal(err)
		}
		var id string
		if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
			return tx.QueryRow(`SELECT id FROM library_item WHERE kind='series'`).Scan(&id)
		}); err != nil {
			t.Fatal(err)
		}
		f.write(t, "Show/TVSHOW.NFO", nfo)
		if _, err := Run(t.Context(), 7, f.store, f.coord, f.claim(t), f.ownerResolve, Options{}); err != nil {
			t.Fatal(err)
		}
		if err := f.store.WithTx(t.Context(), func(tx *sql.Tx) error {
			var series, episodes int
			if err := tx.QueryRow(`SELECT count(*) FROM library_item WHERE kind='series' AND id=?`, id).Scan(&series); err != nil {
				return err
			}
			if err := tx.QueryRow(`SELECT count(*) FROM episode_position`).Scan(&episodes); err != nil {
				return err
			}
			if series != 1 || episodes != 1 {
				t.Fatalf("series=%d episodes=%d", series, episodes)
			}
			return nil
		}); err != nil {
			t.Fatal(err)
		}
	})
}
