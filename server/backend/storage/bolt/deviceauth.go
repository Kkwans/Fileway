package bolt

import (
	"encoding/json"
	"github.com/Kkwans/nas-file-browser/backend/deviceauth"
	"github.com/asdine/storm/v3"
	"go.etcd.io/bbolt"
)

type deviceAuthorizationBackend struct{ db *storm.DB }

// A dedicated bucket leaves legacy settings, users and schema migration intact.
// Bolt commits the authorization and refresh rotation in a single transaction.
func (b deviceAuthorizationBackend) Update(update func(*deviceauth.State) error) error {
	return b.db.Bolt.Update(func(tx *bbolt.Tx) error {
		bucket, err := tx.CreateBucketIfNotExists([]byte("fileway-device-auth-v1"))
		if err != nil {
			return err
		}
		state := new(deviceauth.State)
		previous := bucket.Get([]byte("state"))
		if len(previous) > 0 {
			if err = json.Unmarshal(previous, state); err != nil {
				return err
			}
		}
		if err = update(state); err != nil {
			return err
		}
		next, err := json.Marshal(state)
		if err != nil {
			return err
		}
		// Most authenticated calls only read. Avoid a write/fsync when unchanged.
		if string(previous) == string(next) {
			return nil
		}
		return bucket.Put([]byte("state"), next)
	})
}
