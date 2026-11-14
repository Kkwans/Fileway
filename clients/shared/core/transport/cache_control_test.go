package transport

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestManagedCacheR022StatsAreReadOnlyAndScopeRegisteredOwner(t *testing.T) {
	root := t.TempDir()
	b := managedR021Broker(t, CacheConfig{Directory: root, MaxBytes: 64, Hours: 24, Managed: true})
	id, value := managedR021Lease(t, b, "https://example.invalid", "owner-a")
	lease := managedR021LookupLease(t, b, value)
	key := cacheHash("version") + "-0"
	if err := b.cache.writeChunk(context.Background(), lease.cacheGroup(), key, []byte("active")); err != nil {
		t.Fatal(err)
	}
	active := b.cache.chunkPath(lease.cacheGroup(), key, 6)
	inactive := filepath.Join(b.cache.config.Directory, cacheHash("owner-a"), cacheHash("inactive"), "fileway-chunk-"+cacheHash("version")+"-0-5")
	other := filepath.Join(b.cache.config.Directory, cacheHash("owner-b"), cacheHash("other"), "fileway-chunk-"+cacheHash("version")+"-0-4")
	partial := filepath.Join(filepath.Dir(inactive), "fileway-partial-123")
	unknown := filepath.Join(filepath.Dir(inactive), "download.jpg")
	for path, data := range map[string][]byte{
		inactive: []byte("stale"), other: []byte("else"), partial: []byte("tmp"), unknown: bytes.Repeat([]byte("u"), 128),
	} {
		managedR021File(t, path, data)
	}
	old := time.Now().Add(-48 * time.Hour).Truncate(time.Second)
	for _, path := range []string{active, inactive, other, partial} {
		if err := os.Chtimes(path, old, old); err != nil {
			t.Fatal(err)
		}
	}
	accounting := b.cache.bytes
	all, err := b.CacheStatsForRoot("", "")
	if err != nil || all.Bytes != 18 || all.Files != 4 || all.Protected != 1 || all.ProtectedBytes != 6 || !all.ActivePool {
		t.Fatalf("managed usage changed scope: %+v, %v", all, err)
	}
	owned, err := b.CacheStatsForRoot(root, "owner-a")
	if err != nil || owned.Bytes != 14 || owned.Files != 3 || owned.Protected != 1 || owned.ProtectedBytes != 6 || !owned.ActivePool {
		t.Fatalf("owner usage changed scope: %+v, %v", owned, err)
	}
	if b.cache.bytes != accounting {
		t.Fatal("stats changed the cache's mutable accounting")
	}
	for _, path := range []string{active, inactive, other, partial} {
		info, err := os.Stat(path)
		if err != nil || !info.ModTime().Equal(old) {
			t.Fatalf("stats evicted or touched cache data: %s, %v", path, err)
		}
	}
	if _, err = b.CacheStatsForRoot(root, ""); err == nil {
		t.Fatal("explicit root without owner was accepted")
	}
	b.CloseSession(id)
}

func TestManagedCacheR022HistoricalRootControlNeverConfiguresOrCreates(t *testing.T) {
	b, err := New()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = b.Close() })
	missing := filepath.Join(t.TempDir(), "missing-cache-root")
	for _, operation := range []func() error{
		func() error {
			stats, err := b.CacheStatsForRoot(missing, "owner-a")
			if err == nil && (stats.Bytes != 0 || stats.Files != 0 || stats.ActivePool) {
				t.Fatalf("missing root usage was fabricated: %+v", stats)
			}
			return err
		},
		func() error {
			_, err := b.CleanCacheOwnerInRoot("owner-a", missing, false)
			return err
		},
	} {
		if err = operation(); err != nil {
			t.Fatal(err)
		}
		managedR021Absent(t, missing)
		if b.cache != nil {
			t.Fatal("historical root control configured the current pool")
		}
	}
	root := t.TempDir()
	group := filepath.Join(root, managedCacheDirectory, cacheHash("owner-a"), cacheHash("group"))
	owned := filepath.Join(group, "fileway-chunk-"+cacheHash("version")+"-0-4")
	other := filepath.Join(root, managedCacheDirectory, cacheHash("owner-b"), cacheHash("group"), filepath.Base(owned))
	unknown := filepath.Join(group, "personal.jpg")
	managedR021File(t, owned, []byte("aaaa"))
	managedR021File(t, other, []byte("bbbb"))
	managedR021File(t, unknown, []byte("private"))
	stats, err := b.CacheStatsForRoot(root, "owner-a")
	if err != nil || stats.Bytes != 4 || stats.Files != 1 || stats.ActivePool || stats.Protected != 0 {
		t.Fatalf("historical owner usage returned %+v, %v", stats, err)
	}
	remaining, err := b.CleanCacheOwnerInRoot("owner-a", root, false)
	if err != nil || remaining.Bytes != 4 || remaining.Files != 1 {
		t.Fatalf("historical cleanup changed its remaining-pool contract: %+v, %v", remaining, err)
	}
	managedR021Absent(t, owned)
	managedR021Contents(t, other, []byte("bbbb"))
	managedR021Contents(t, unknown, []byte("private"))
	if b.cache != nil {
		t.Fatal("historical cleanup replaced the active configuration")
	}
}

func TestManagedCacheR022InactiveOwnerCleanupPreservesActiveGroups(t *testing.T) {
	root := t.TempDir()
	b := managedR021Broker(t, CacheConfig{Directory: root, MaxBytes: 64, Hours: 24, Managed: true})
	_, value := managedR021Lease(t, b, "https://example.invalid", "owner-a")
	lease := managedR021LookupLease(t, b, value)
	key := cacheHash("version") + "-0"
	if err := b.cache.writeChunk(context.Background(), lease.cacheGroup(), key, []byte("active")); err != nil {
		t.Fatal(err)
	}
	active := b.cache.chunkPath(lease.cacheGroup(), key, 6)
	partial := filepath.Join(filepath.Dir(active), "fileway-partial-456")
	inactive := filepath.Join(b.cache.config.Directory, cacheHash("owner-a"), cacheHash("inactive"), "fileway-chunk-"+cacheHash("version")+"-0-5")
	other := filepath.Join(b.cache.config.Directory, cacheHash("owner-b"), cacheHash("other"), "fileway-chunk-"+cacheHash("version")+"-0-4")
	managedR021File(t, partial, []byte("tmp"))
	managedR021File(t, inactive, []byte("stale"))
	managedR021File(t, other, []byte("else"))
	if _, err := b.CleanCacheOwnerInRoot("owner-a", root, false); err == nil {
		t.Fatal("explicit current root bypassed strict active-owner protection")
	}
	if _, err := b.CleanCacheOwnerInRoot("owner-a", root, true); err != nil {
		t.Fatal(err)
	}
	managedR021Absent(t, inactive)
	managedR021Contents(t, active, []byte("active"))
	managedR021Contents(t, partial, []byte("tmp"))
	managedR021Contents(t, other, []byte("else"))
	stats, err := b.CacheStatsForRoot(root, "owner-a")
	if err != nil || stats.Bytes != 9 || stats.Files != 2 || stats.Protected != 2 || stats.ProtectedBytes != 9 {
		t.Fatalf("inactive cleanup removed active owner data: %+v, %v", stats, err)
	}
}

func TestManagedCacheR022RootAliasCannotBypassActiveProtection(t *testing.T) {
	root := t.TempDir()
	b := managedR021Broker(t, CacheConfig{Directory: root, MaxBytes: 64, Hours: 24, Managed: true})
	_, _ = managedR021Lease(t, b, "https://example.invalid", "owner-a")
	alias := filepath.Join(t.TempDir(), "cache-alias")
	if err := os.Symlink(root, alias); err != nil {
		t.Skip("directory symlinks are unavailable in this environment")
	}
	stats, err := b.CacheStatsForRoot(alias, "owner-a")
	if err != nil || !stats.ActivePool {
		t.Fatalf("active root alias was misidentified: %+v, %v", stats, err)
	}
	if _, err = b.CleanCacheOwnerInRoot("owner-a", alias, false); err == nil {
		t.Fatal("root alias bypassed strict active-owner protection")
	}
	if _, err = b.CleanCacheOwnerInRoot("owner-a", alias, true); err != nil {
		t.Fatal(err)
	}
}

func TestManagedCacheR022HistoricalRootRejectsUnownedRootAndOwnerLinks(t *testing.T) {
	b := managedR021Broker(t, CacheConfig{Directory: t.TempDir(), MaxBytes: 64, Hours: 24, Managed: true})
	root := t.TempDir()
	pool := filepath.Join(root, managedCacheDirectory)
	managedR021File(t, pool, []byte("unowned collision"))
	if _, err := b.CacheStatsForRoot(root, "owner-a"); err == nil {
		t.Fatal("regular file was accepted as a managed pool")
	}
	if _, err := b.CleanCacheOwnerInRoot("owner-a", root, false); err == nil {
		t.Fatal("regular file was accepted for cleanup")
	}
	managedR021Contents(t, pool, []byte("unowned collision"))
	if _, err := b.CacheStatsForRoot("relative-root", "owner-a"); err == nil {
		t.Fatal("relative registered root was accepted")
	}
	t.Run("managed root link", func(t *testing.T) {
		root := t.TempDir()
		foreign := t.TempDir()
		owned := filepath.Join(foreign, cacheHash("owner-a"), cacheHash("group"), "fileway-chunk-"+cacheHash("version")+"-0-4")
		managedR021File(t, owned, []byte("keep"))
		if err := os.Symlink(foreign, filepath.Join(root, managedCacheDirectory)); err != nil {
			t.Skip("directory symlinks are unavailable in this environment")
		}
		if _, err := b.CacheStatsForRoot(root, "owner-a"); err == nil {
			t.Fatal("linked managed root was accepted for usage")
		}
		if _, err := b.CleanCacheOwnerInRoot("owner-a", root, false); err == nil {
			t.Fatal("linked managed root was accepted for cleanup")
		}
		managedR021Contents(t, owned, []byte("keep"))
	})
	t.Run("owner link", func(t *testing.T) {
		root := t.TempDir()
		pool := filepath.Join(root, managedCacheDirectory)
		if err := os.Mkdir(pool, 0700); err != nil {
			t.Fatal(err)
		}
		foreign := t.TempDir()
		owned := filepath.Join(foreign, cacheHash("group"), "fileway-chunk-"+cacheHash("version")+"-0-4")
		managedR021File(t, owned, []byte("keep"))
		if err := os.Symlink(foreign, filepath.Join(pool, cacheHash("owner-a"))); err != nil {
			t.Skip("directory symlinks are unavailable in this environment")
		}
		stats, err := b.CacheStatsForRoot(root, "owner-a")
		if err != nil || stats.Bytes != 0 || stats.Files != 0 {
			t.Fatalf("linked owner was enumerated: %+v, %v", stats, err)
		}
		if _, err := b.CleanCacheOwnerInRoot("owner-a", root, false); err != nil {
			t.Fatal(err)
		}
		managedR021Contents(t, owned, []byte("keep"))
	})
}
