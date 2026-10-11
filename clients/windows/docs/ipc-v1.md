# Windows private control protocol v1

The WinUI application starts the fixed, co-located Go Host executable with private inherited stdin/stdout pipes. There is no control TCP listener. The parent owns a kill-on-close Job Object. stderr contains only bounded, redacted diagnostics; stdout is exclusively framed protocol data. The shared Go module remains the implementation of authentication, network routing and resource leases.

## Frames and identity

Each frame is an unsigned 32-bit **little-endian** byte count followed by that many UTF-8 JSON bytes. Length must be between 1 and 16,777,216 inclusive, checked before allocation. EOF, truncated frames, invalid UTF-8 and malformed envelopes fail the connection. No request body is logged.

```json
{"protocolMajor":1,"protocolMinor":0,"requestId":"unique-id","generation":0,"op":"open","parameters":{"baseUrl":"https://example.invalid","network":"direct"}}
```

- Major must equal 1; unsupported major returns `ProtocolMismatch` without invoking the core. Minor 0 is the initial contract; optional features are negotiated in `init`.
- requestId is a nonempty ASCII identifier of at most 128 bytes, unique among accepted outstanding requests. generation is a nonnegative signed 64-bit integer echoed unchanged. Neither grants authority.
- `session`, when present, is a top-level immutable core session handle. `parameters` is an object with operation-specific core fields. It may not contain `op`, `session` or `requestId`; the Host supplies those fields.
- `init` is required before business operations. Its result contains `protocolMajor`, `protocolMinor`, `coreProtocol` (currently 1), `hostVersion`, `buildCommit`, Go/module build metadata and explicit Host capabilities. A missing build commit is reported as `unknown`, never invented.
- `health` is a Host-only operation. `cancel` has `parameters.targetRequestId`; it cancels the accepted target, including queued work. The cancel request has its own distinct requestId. Cancellation acknowledgement does not assert that a mutation was undone.
- `shutdown` is terminal for the Host even though the reusable shared Engine can initialize again. After the response, new business requests are rejected, accepted work is canceled/drained with a bound, and the Engine is shut down. Pipe EOF also initiates shutdown.

## Results and errors

```json
{"protocolMajor":1,"protocolMinor":0,"requestId":"unique-id","generation":0,"ok":false,"error":"request queue is full","errorInfo":{"code":"Busy","message":"request queue is full","retryable":true}}
```

Success has `ok:true` and a JSON `result` (including null). Failure preserves the core's existing `error` string and adds `errorInfo` with a stable `code`, safe message, optional `httpStatus` and `retryable`. Classification must use typed errors or actual HTTP status, never English string matching. A core error not yet typed is explicitly `CoreRejected` and not automatically retried; it is not guessed to be a network error.

Host codes include `ProtocolMismatch`, `HandshakeRequired`, `InvalidRequest`, `DuplicateRequest`, `Busy`, `Canceled`, `ResponseTooLarge`, `HostClosing`, `HostFailure` and `CoreRejected`. Higher-level HTTP responses retain their actual status/body as core results; the .NET adapter classifies authentication, authorization and business failures by status and typed server fields. Loss of a response to a dispatched mutation becomes `OutcomeUnknown` in its use case, never a generic automatic retry.

## Scheduling and cancellation

- At most 8 business operations run, with at most 64 additional accepted queued operations. Saturation returns `Busy` with matching identity; it does not block the reader.
- `cancel`, `close_session`, `health` and `shutdown` use a separate bounded control path so they can proceed while business workers are occupied. init is handled before business dispatch.
- Each accepted request produces at most one response. stdout writes are serialized, and returned frames are bounded. The reader keeps receiving cancellations while operations execute.
- The core's ordinary command limit remains approximately 1 MiB; the Host does not raise it or route file/media bytes through JSON. This first Windows client does not use the raw-body exception.
- A queued canceled request must not call the server. Completion already confirmed by the core survives a late cancel. Closing a session prevents queued requests from recreating or using that session.
- The .NET client drops obsolete presentation callbacks by generation but still records confirmed business results. Killing/restarting the Host invalidates every session and lease.

## Optional managed media cache

Windows may configure the shared cache with `cache_configure`, supplying
`cacheConfig.directory`, `maxBytes`, `hours` and optional `managed:true`. Managed
mode owns only its `fileway-cache-v1` child. Omitted `managed` retains the legacy
client layout. The Windows default budget is 5,000,000,000 bytes; zero disables
disk caching and the shared ceiling is 1 TiB. A configured directory cannot change
within a Host process; new directory settings take effect after restart.

The existing `lease` operation accepts optional `cacheKey` and `cacheOwner` from
the trusted account-aware adapter. Raw media stays on the loopback resource path,
not in JSON. `lease_stats` requires the owning session and URL, reporting
`upstreamBytes`, `cacheReadBytes`, `deliveredBytes`, `elapsedMillis` and additive
`cacheWriteFailures`. These are byte counters, not buffered media timestamps.

The `preview` operation accepts the same optional cache fields while preserving
its existing path/scope fields. `scope:"contain"` uses the server's fixed 512-pixel
route. Raw and preview representations share the data budget without sharing
content identities. A failed preview never falls back to the original file.

`cache_stats` is a read-only, sessionless operation. Without parameters it reports
the current pool; optional `cacheRoot` plus required `cacheOwner` reports that
registered owner in another application root. It returns `bytes`, `files`,
`protected`, `protectedBytes` and `activePool`; the last flag identifies current
pool aliases to avoid double-counting. It does not create directories, evict
blocks or touch LRU timestamps. Missing historical pools report no data; the
adapter separately retains unavailable roots instead of guessing cleanup success.

`cache_cleanup_owner` takes `cacheOwner` without a live session, allowing durable
logout recovery after credentials and sessions have been retired. It refuses
cleanup while that owner still has live media leases. It does not delete other
owners or unknown files. This control capability is reachable only through the
parent's private inherited pipes, never through a remote HTTP control port.

Optional `cacheRoot` targets a registered historical application root, and
`inactiveOnly:true` clears only inactive groups. Omitting it retains strict logout
behavior. Cleanup replies retain the preexisting remaining-pool totals; query
owner statistics separately. Historical OS work uses a retained single-flight
job with bounded caller waiting; this does not claim the OS call is cancellable.

See [cache storage](cache-storage.md) for version, quota and failure semantics.

## Verification

The Host is tested with in-memory framed streams and a fake core boundary, plus a real subprocess integration test using the shared Engine and a local HTTP fixture. Cover length/UTF-8/truncation, handshake/version mismatch, duplicate identities, eight workers/64 queued saturation, cancellation while saturated, queued cancellation, session close, response bounds, EOF/shutdown and clean process exit. Never use personal server credentials in tests.
