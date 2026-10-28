package bridge

import (
	"context"
	"crypto/rand"
	"errors"
	"time"

	"github.com/Kkwans/nas-file-browser-client/core/remote"
)

func (e *Engine) executeRemote(ctx context.Context, command Command) (any, error) {
	if command.Op == "remote_identity" {
		return remote.GenerateIdentity(time.Now())
	}
	if command.Op == "remote_start" {
		if command.RemoteConfig == nil {
			return nil, errors.New("remote configuration required")
		}
		e.mu.Lock()
		if e.remote != nil {
			e.mu.Unlock()
			return nil, errors.New("remote host already running")
		}
		e.remoteEpoch++
		epoch := e.remoteEpoch
		e.mu.Unlock()
		host, err := remote.Start(*command.RemoteConfig)
		if err != nil {
			return nil, err
		}
		e.mu.Lock()
		if e.remoteEpoch != epoch || e.remote != nil || ctx.Err() != nil {
			e.mu.Unlock()
			_ = host.Close()
			return nil, errors.New("remote generation changed")
		}
		handle := rand.Text()
		e.remote, e.remoteHandle = host, handle
		e.mu.Unlock()
		// String handles use the same canceled-result handoff as media leases.
		return handle, nil
	}
	if command.Op == "remote_stop" {
		e.mu.Lock()
		if e.remote == nil {
			e.mu.Unlock()
			return nil, nil
		}
		if command.RemoteHandle == "" || e.remoteHandle != command.RemoteHandle {
			e.mu.Unlock()
			return nil, errors.New("remote generation changed")
		}
		host := e.remote
		e.remote, e.remoteHandle = nil, ""
		e.remoteEpoch++
		e.mu.Unlock()
		return nil, host.Close()
	}
	e.mu.Lock()
	host := e.remote
	valid := host != nil && command.RemoteHandle != "" && command.RemoteHandle == e.remoteHandle
	e.mu.Unlock()
	if !valid {
		return nil, errors.New("remote host unavailable or changed")
	}
	switch command.Op {
	case "remote_connection":
		return host.Connected(command.RemoteConnectionID, command.RemoteControllerID), nil
	case "remote_descriptor":
		return host.Descriptor(), nil
	case "remote_invite":
		return host.Invite()
	case "remote_wait":
		// Idle waits have no polling work. A shorter native wait leaves headroom
		// inside the bridge request's deadline and is cancellable on background.
		wait, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		event, err := host.Wait(wait)
		if errors.Is(err, context.DeadlineExceeded) && ctx.Err() == nil {
			return remote.Event{Type: "idle"}, nil
		}
		return event, err
	case "remote_pair_complete":
		return nil, host.CompletePair(command.RemotePairRequestID, command.RemoteSaved)
	case "remote_revoke":
		host.Revoke(command.RemoteControllerID)
		return nil, nil
	case "remote_publish":
		if command.RemoteState == nil {
			return nil, errors.New("projected TV state required")
		}
		return nil, host.Publish(*command.RemoteState)
	case "remote_reply":
		if command.RemoteResult == nil {
			return nil, errors.New("command result required")
		}
		return nil, host.Reply(command.RemoteConnectionID, command.RemoteControllerID, *command.RemoteResult)
	default:
		return nil, errors.New("unknown remote operation")
	}
}
