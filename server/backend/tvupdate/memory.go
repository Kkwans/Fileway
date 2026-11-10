package tvupdate

import (
	"io"
	"slices"
	"time"
)

const (
	cacheBlockSize = 64 << 10
	cacheBlocks    = (128 << 20) / cacheBlockSize
)

// APK blocks belong to exactly one preparation or verified artifact until its
// last reader finishes. Blocks are reused and never exposed as resource paths.
type cachedAPK struct {
	blocks  [][]byte
	size    int64
	usedAt  time.Time
	readers int
	retired bool
}

func (apk *cachedAPK) ReadAt(target []byte, offset int64) (int, error) {
	if len(target) == 0 {
		return 0, nil
	}
	if offset < 0 || offset >= apk.size {
		return 0, io.EOF
	}
	length := int(min(int64(len(target)), apk.size-offset))
	for copied := 0; copied < length; {
		block := apk.blocks[offset/cacheBlockSize]
		count := copy(target[copied:length], block[offset%cacheBlockSize:])
		copied += count
		offset += int64(count)
	}
	if length < len(target) {
		return length, io.EOF
	}
	return length, nil
}

// reserveAPK is called with s.mu held. Only unpinned, completed downloads may
// be evicted. Retired readers and a canceled writer still consume their blocks.
func (s *Service) reserveAPK(size int64, now time.Time) *cachedAPK {
	needed := int((size + cacheBlockSize - 1) / cacheBlockSize)
	if needed <= 0 || needed > s.blockLimit {
		return nil
	}
	available := len(s.freeBlocks) + s.blockLimit - s.allocatedBlocks
	type eviction struct {
		key string
		apk *cachedAPK
	}
	var evictable []eviction
	if available < needed {
		for key, d := range s.downloads {
			if d.ready && d.apk.readers == 0 {
				evictable = append(evictable, eviction{key, d.apk})
				available += len(d.apk.blocks)
			}
		}
		if available < needed {
			return nil
		}
		slices.SortFunc(evictable, func(a, b eviction) int { return a.apk.usedAt.Compare(b.apk.usedAt) })
		for _, old := range evictable {
			if len(s.freeBlocks)+s.blockLimit-s.allocatedBlocks >= needed {
				break
			}
			s.dropDownload(old.key)
		}
	}
	apk := &cachedAPK{blocks: make([][]byte, needed), size: size, usedAt: now}
	for index := range apk.blocks {
		last := len(s.freeBlocks) - 1
		if last >= 0 {
			apk.blocks[index] = s.freeBlocks[last]
			s.freeBlocks[last] = nil
			s.freeBlocks = s.freeBlocks[:last]
		} else {
			apk.blocks[index] = make([]byte, cacheBlockSize)
			s.allocatedBlocks++
		}
	}
	return apk
}

func (s *Service) retireAPK(apk *cachedAPK) {
	apk.retired = true
	if apk.readers == 0 {
		s.recycleAPK(apk)
	}
}

func (s *Service) recycleAPK(apk *cachedAPK) {
	if s.ctx.Err() == nil {
		s.freeBlocks = append(s.freeBlocks, apk.blocks...)
	}
	apk.blocks = nil
}

func (s *Service) dropDownload(key string) {
	d := s.downloads[key]
	delete(s.downloads, key)
	for id, h := range s.handles {
		if h.key == key {
			delete(s.handles, id)
		}
	}
	d.cancel()
	if d.ready {
		s.retireAPK(d.apk)
	}
}
