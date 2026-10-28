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
