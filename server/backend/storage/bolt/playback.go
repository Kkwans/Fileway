package bolt

import (
	"errors"

	"github.com/asdine/storm/v3"
	"github.com/asdine/storm/v3/q"

	"github.com/Kkwans/nas-file-browser/backend/playback"
)

type playbackRecord struct {
	ID        string `storm:"id"`
	UserID    uint   `storm:"index"`
	Path      string `storm:"index"`
	Identity  string
	Position  float64
	Duration  float64
	UpdatedAt int64 `storm:"index"`
}

type playbackBackend struct {
	db *storm.DB
}

func (backend playbackBackend) Recent(userID uint, limit int) ([]*playback.Entry, error) {
	var records []playbackRecord
	err := backend.db.Select(q.Eq("UserID", userID)).OrderBy("UpdatedAt", "ID").Reverse().Limit(limit).Find(&records)
	if err != nil && !errors.Is(err, storm.ErrNotFound) {
		return nil, err
	}
	result := make([]*playback.Entry, 0, len(records))
	for _, record := range records {
		result = append(result, record.entry())
	}
	return result, nil
}

func (backend playbackBackend) GetByID(id string) (*playback.Entry, error) {
	var record playbackRecord
	if err := backend.db.One("ID", id, &record); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return nil, playback.ErrNotExist
		}
		return nil, err
	}
	return record.entry(), nil
}

func (backend playbackBackend) Save(entry *playback.Entry) error {
	return backend.db.Save(newPlaybackRecord(entry))
}

func (backend playbackBackend) Update(entry *playback.Entry) error {
	record := newPlaybackRecord(entry)
	transaction, err := backend.db.Begin(true)
	if err != nil {
		return err
	}
	defer func() { _ = transaction.Rollback() }()
	if err := transaction.Update(record); err != nil {
		return err
	}
	// Storm's Update deliberately skips zero values.  Force the playback
	// fields in the same writable transaction so seeking to 0 and a zero
	// duration are persisted atomically with the rest of the record.
	if err := transaction.UpdateField(&playbackRecord{ID: entry.ID}, "Position", entry.Position); err != nil {
		return err
	}
	if err := transaction.UpdateField(&playbackRecord{ID: entry.ID}, "Duration", entry.Duration); err != nil {
		return err
	}
	return transaction.Commit()
}

func (backend playbackBackend) Delete(id string) error {
	if err := backend.db.DeleteStruct(&playbackRecord{ID: id}); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return playback.ErrNotExist
		}
		return err
	}
	return nil
}

func newPlaybackRecord(entry *playback.Entry) *playbackRecord {
	return &playbackRecord{
		ID: entry.ID, UserID: entry.UserID, Path: entry.Path,
		Identity: entry.Identity, Position: entry.Position,
		Duration: entry.Duration, UpdatedAt: entry.UpdatedAt,
	}
}

func (record *playbackRecord) entry() *playback.Entry {
	return &playback.Entry{
		ID: record.ID, UserID: record.UserID, Path: record.Path,
		Identity: record.Identity, Position: record.Position,
		Duration: record.Duration, UpdatedAt: record.UpdatedAt,
	}
}

func (backend playbackBackend) All(userID uint) ([]*playback.Entry, error) {
	var records []playbackRecord
	err := backend.db.Select(q.Eq("UserID", userID)).Find(&records)
	if err != nil && !errors.Is(err, storm.ErrNotFound) {
		return nil, err
	}
	result := make([]*playback.Entry, 0, len(records))
	for _, record := range records {
		result = append(result, record.entry())
	}
	return result, nil
}
