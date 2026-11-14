# TMDB service setup

The local catalog and NFO scanner work without a provider. The server can
store individually supplied TMDB application credentials after the operator
configures an optional credential vault. A v3 API key and an API read access
token are accepted. The provider host file is separate from both.

## Vault and network settings

- `mediaProviderKeyFile` points to an absolute path containing 32 random binary
  bytes. Use a service-owned private `.fileway-media` directory distinct from
  the catalog directory. Keep the key outside media folders and outside the
  catalog backup. On Unix, the key must be owned by the service account with no
  group or other permissions. On Windows, restrict the file ACL to that service
  account, SYSTEM and local administrators.
- `mediaTMDBHostsFile` optionally points to a text file with lines of the form
  `IP DOMAIN`. Allowed domains are `api.themoviedb.org` (including the
  `api.tmdb.org` alias) and `image.tmdb.org`. Each domain accepts up to four
  IPv4 or IPv6 addresses. Leave a domain absent to use normal DNS.

These are Fileway service settings. The mapping changes the dial address for
TMDB requests only. The URL hostname, TLS server identity, and system
certificate verification stay intact. Loopback, private and other
non-public addresses are rejected, and credential-bearing requests may not
redirect. Stop and restart the Fileway server to apply changes to these files.
An unreadable or malformed configuration leaves local file and NFO access
available.

The catalog stores sealed connections and references. Keep a separate recovery
copy of the existing vault key. A lost key requires entering the provider
credentials again. When setting credentials through the API, use a trusted
Fileway server connection. No shared project API key is distributed.

[Artwork configuration](https://developer.themoviedb.org/docs/image-basics) describes
the CDN URLs and size variants. Fileway downloads the selected `w500` poster
or `w780` backdrop when a client first requests it. Sanitized images go to a
512 MiB bounded, rebuildable `artwork-cache` directory next to the catalog
database. Cache entries survive restart and remain usable when TMDB is temporarily
unavailable. Access requires current catalog and source authorization, including
on a cache hit. Clearing the artwork cache leaves metadata and match decisions
intact. Keep the catalog parent on local service-owned storage.

[TMDB application authentication](https://developer.themoviedb.org/docs/authentication-application)
