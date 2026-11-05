# Android companion control core

Shared by the existing phone application and the independent TV application;
minimum API28, no Compose dependency and no media player ownership. Both Gradle
entrypoints include this module as `:remote-core`.

- `TvRemoteHost`: foreground JNI host lifecycle, DNS-SD advertisement, durable
  controller approval and serial dispatch to the TV gateway.
- `TvRemoteClient`: scoped certificate trust, QR pairing, installation-key
  challenge, authoritative snapshots and bounded pending commands.
- `DeviceAuthorization`: fixed-origin instance proof, independent TV credentials,
  durable refresh intent and source identity checks. It never receives a phone
  password or bearer token.
- `RemoteDatabase`: separate `fileway-remote.db`, version 1. Existing account DB
  schemas remain untouched. Only pairing/grant metadata is stored in Room;
  private keys and device credentials use the existing Keystore-backed vault.
- `ControllerIdentity`: phone P-256 signing key inside Android Keystore, independent
  of current NAS login. Pairing persists when the phone switches accounts.

NAS discovery and TV discovery share the bounded API28-compatible
`client-core/data/LanServiceBrowser`, with distinct DNS-SD service types. An
advertisement is only a candidate. A changed fingerprint requires explicit
pairing again; it never authorizes forwarding an existing credential.

The phone's CameraX scanner belongs in the phone application, not this module or
the TV. ZXing provides QR encoding/decoding; OkHttp provides the phone's WSS/TLS
transport. Native control uses the existing Go core, separate from media leases.

Protocol and threat boundaries: `clients/shared/core/remote/README.md`.
Server contract: `server/docs/device-authorization.md`.

`MediaHandoff.kt` supplies the immutable media description and complete queue
transaction used by native handoff. It preserves opaque wire bytes, creates an
ordered SHA-256 manifest and pages data below 40 KiB. The receiver binds one
connection/session/media generation, permits identical chunk retries, rejects
gaps and changed retries, and commits only a complete matching manifest/current
item. Transactions expire after two minutes; limits are 10,000 items and 2 MiB.
Exceeding a limit is an explicit error, never silent queue truncation. Callers
must still check source instance, account permissions and file version before
opening media; a queue digest grants no resource authority. This shared data
layer alone does not advertise an end-to-end native-handoff capability.

A TV pairing request opens its UI immediately, independently of invitation
availability. `TvHostState.phase` describes the foreground host; `pairingOpen`,
`pairingBusy` and `pairingError` describe the explicit invitation operation.
Startup errors, expiry and a lost host retain an actionable page instead of
hiding it. Dismissal invalidates pending presentation results; an invitation
from a replaced host is never published to the UI.

The host observes non-VPN Wi-Fi/Ethernet networks, including link-address
changes. Network + local address is the restart identity. Registering a callback
also reports existing networks; that initial notification and VPN-only changes
must not restart an unchanged LAN host or discard its invitation.

Client lifecycle transitions serialize generation, socket ownership, snapshots
and pending command retirement. Callback parsing may run off-main, but a retired
connection cannot publish a snapshot or complete a new connection's commands.
Disconnect invalidates the generation before IO cleanup; it preserves pairing
and never sends playback stop or NAS logout. Empty WebSocket close frames are
acknowledged with a valid close code instead of echoing reserved code 1005.

`RemoteConnectionState.retryable` describes transient transport loss only.
Identity/protocol failures, revoked trust and another controller's ownership
require explicit user action. The phone model retries only its selected device
while the control UI is visible, with bounded exponential delay and fresh
discovery endpoints matched by identity/pin. Manual disconnect suppresses
automatic connection until another explicit connection action. Commands are
not replayed on reconnect; an acknowledged write remains acknowledged, while
a lost response remains unknown. The next full snapshot replaces stale UI.

Discovery pairing uses the optional native `pairingVersion: 2` branch. It sends
an installation-key proof, opens a local TV allow/deny page and displays the same
six-digit comparison code on both devices. The phone requires a matching-code
confirmation after TV approval before saving trust or opening control. A public
endpoint claiming PAIRED is insufficient to authenticate a discovered TV. The
TV retains its code until the approved phone connects or the presentation expires.
Already paired devices retain one-action connection; physical QR pairing keeps
its existing high-entropy invitation proof. Unsupported TVs retain a visible QR
fallback rather than silently opening the camera.

Cancellation/expiry cannot commit late keys. TV key persistence, revocation and
replacement-host loading share a mutex; a temporary Room row cannot be loaded
as approved trust while its native ACK is pending. Phone selections wait for
the preceding bounded cancellation, and an uncertain start retry reuses the same
signed nonce instead of creating another prompt. NAS login remains a separate
explicit authorization after pairing.

The phone keeps useful saved pairings but has one active target. Connecting,
pairing or controlling a TV blocks another target until explicit disconnect or
cancel. The model and transport both enforce this before retiring the active
connection. Device UI offers the current connection first; alternative devices
are selectable after disconnect. There is no seamless switch or automatic
fallback to the previous TV.
