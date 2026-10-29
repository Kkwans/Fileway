# Fileway companion control

Protocol 1 is a direct, foreground-only phone-to-TV control channel. The TV owns
its media player and independently authenticated NAS session. Closing the phone
connection never logs out or stops the TV.

The TV advertises `_fileway-tv._tcp.`; NAS discovery uses `_fileway._tcp.`. TXT
identity hints are untrusted. A short-lived TV QR (`fileway://tv/pair?v=1&data=…`)
binds the phone to one installation SPKI fingerprint and invitation secret.
Pairing requires an invitation HMAC and durable TV-side storage before its ACK.
Subsequent connections use a one-time nonce signed by the phone's Keystore key.
The TV's P-256 certificate DNS name remains stable across LAN address changes.
Android's scoped TLS trust checks the exact QR-pinned installation key, validity,
self-signature, purpose, and the normal hostname verification. No global trust
override or TLS bypass is installed.

The host listens on one concrete local interface. There is one active controller,
at most eight sockets and sixteen paired controllers. Requests reject cross-origin
browser access, arbitrary hostnames and unexpected query parameters. WebSocket
subprotocol: `fileway.remote.v1`; frame bound 64 KiB, snapshot bound 48 KiB.
Messages never contain NAS credentials, media URLs, content URIs or shell commands.

`command` contains a unique `commandId`, `connectionId`, `sessionEpoch`,
`mediaEpoch`, whitelisted `operation` and object `payload`. Android verifies active
trust and epochs before calling the real model/player on Main. The UI projects
public state snapshots instead of serializing internal session objects. Browse
items use opaque resource IDs; only the TV resolves them against its own state.

Playback snapshots optionally report `audioDecoder`, `audioRenderedBuffers` and
`audioSkippedBuffers` for diagnostics. Counts belong to the current audio renderer
and may reset when it changes; they are buffers, not PCM frames or elapsed time.
Rendered buffers establish delivery to the audio sink, not audible speakers,
passthrough quality or physical output. Older peers may omit these additive fields.

`ACCEPTED` acknowledges queuing. `COMPLETED` acknowledges execution by Android;
player state remains authoritative for eventual seek/track/load completion.
`REJECTED` means the operation was not applied; `UNKNOWN` means its result is
uncertain and must be reconciled through a snapshot or `device.commandStatus`.
Identical command IDs are deduplicated for five minutes. Reconnection never
automatically replays state-changing commands. Queues, frame size, HTTP request
rate, pending commands and result retention are bounded.

The native host is accessed only by generation-bound JNI handles. Canceled
`remote_start` results release the created host. Lifecycle stop tears down control
discovery/sockets while preserving paired keys, TV account and playback state.
Independent server authorization is described in `server/docs/device-authorization.md`.
