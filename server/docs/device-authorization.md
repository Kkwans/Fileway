# TV device authorization, version 1

The existing authenticated phone approves a TV installation. The TV exchanges a
signed challenge for its own revocable media session. Phone passwords and bearer
tokens never cross the phone-to-TV control channel. This is an application
protocol, not an OAuth or DPoP implementation.

## Endpoints

All paths are relative to the configured server base path. JSON requests are
bounded to 8 KiB. Credential responses use `Cache-Control: no-store`.

| Method and path | Authentication | Result |
| --- | --- | --- |
| GET `/api/instance` | Existing user | Persisted instance ID and P-256 proof public key |
| POST `/api/device-auth/instance-proof` | None | Signature over a fresh caller nonce and instance ID |
| POST `/api/device-auth/requests` | None | Two-minute request bound to TV SPKI and installation ID |
| POST `/api/device-auth/requests/{id}/approve` | Existing user, never a device session | Approve exactly the expected TV ID/key for the approving account |
| POST `/api/device-auth/exchange` | TV signature over request challenge and nonce | Independent access JWT and rotating refresh credential |
| POST `/api/device-auth/refresh` | Refresh credential and TV signature | Rotated refresh credential and new access JWT |
| GET `/api/device-sessions` | Existing user | Own public device session metadata |
| DELETE `/api/device-sessions/{id}` | Existing user | Revoke own device session |

The phone reads instance identity through its authenticated transport and sends
the expected public key over the trusted TV channel. The TV verifies a fresh
instance signature at the proposed origin before exchanging credentials. A
discovered address alone never authorizes transferring an existing credential.
Existing plain HTTP deployments retain their transport limitations; this proof
does not convert HTTP into confidential transport or defeat an active relay.

Proof bytes are UTF-8 JSON string arrays:

```
["fileway.device-auth.v1","instance",serverId,nonce]
["fileway.device-auth.v1","exchange",serverId,requestId,challenge,nonce]
["fileway.device-auth.v1","refresh",serverId,sessionId,refreshCredential,nonce]
```

Sign with P-256 ECDSA/SHA-256, ASN.1 DER signature encoded as unpadded base64url.
SPKI public keys use unpadded base64url. TV ID is the first 16 bytes of SHA-256
over SPKI, lower-case hex. Nonces contain at least 32 characters of entropy-rich
base64url. Request identifiers and challenges are server-generated 256-bit values.

## Persistence and authority

The Bolt bucket `fileway-device-auth-v1` stores instance identity, a distinct
device JWT signing key, pending requests, and sessions. Existing user/settings
records and version 9 schema remain intact. Approval, exchange, refresh rotation
and revocation are atomic transactions. Only refresh hashes are stored; a
server-key HMAC derives a retry's response without storing plaintext credentials.
An identical signed refresh nonce can recover one lost response. An older
credential with a different nonce is rejected. The TV durably records refresh
intent before requesting rotation.

Device JWTs carry `kid=fileway.device.v1`, `sid`, and `scope=media`. They use an
independent key, so a previous server version rejects them. Every authenticated
request checks session owner, expiry and revocation. Normal `/api/renew` keeps
device identity and scope. Legacy phone/Web JWTs retain their existing key and
contract. Device requests cannot obtain admin/execute/write permissions through
renewal or approve another device. Media scope permits browsing, media resources,
playback preparation/progress, and the account's favorites/recent records.

Access expiry follows the server's existing sliding policy. Device refresh
expires after 365 days of inactivity and extends on explicit renewal. Revocation
does not sign out the approving phone. Limits: 128 pending requests per server,
32 active sessions per account. Pending requests expire after two minutes.

TV credentials live in the Android Keystore-backed vault. A separate Room
database holds only grant metadata and pairing records, preserving existing
phone/TV account database schemas. Source switching validates the candidate and
persists its independent grant before replacing the active source. Phone account
changes never automatically switch TV accounts.

## Upgrade and rollback

Deploy this server before enabling phone-authorized TV login. Older servers
return a clear unsupported result; the ordinary TV login remains available.
Rollback preserves the separate Bolt bucket. A rolled-back server rejects device
JWTs; a subsequent compatible upgrade can use the persisted sessions again unless
revoked. Do not clear user data or replace installation/signing keys to recover.
