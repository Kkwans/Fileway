# Metadata provider adapters

`server/backend/mediaprovider` supplies descriptive input to the media catalog.
It neither grants library/source access nor selects a match. Provider numeric IDs
are always paired with provider and entity kind; they are not internal catalog IDs.

## TMDB transport slice

The current adapter implements:

| Operation | Behavior |
| --- | --- |
| Search | Separate movie/TV endpoints, explicit page, title, year and language; movie release-region support. TV has no region search parameter. A single candidate remains a candidate. |
| Fetch | Movie, series, person and collection details; supported requested parts only. Series credits use aggregate credits. The returned ID must match the requested entity. |
| FetchSeason | Uses the known series and season number, including season zero. Retains that parent/number and raw episode data; does not invent episode cut points or replace catalog ordering. |
| Find | IMDb, TVDB and Wikidata identifiers; preserves movie/series/person/season/episode result types instead of selecting the first result. |
| Configuration | Reads official image sizes and the fixed HTTPS TMDB image origin. Image URL construction accepts configured sizes and raster filenames only. |

Documents preserve raw field presence, nulls and empty values for the projection
layer. Their language records the requested locale, not a guarantee that every
returned field was translated. Provider payloads are not catalog HTTP DTOs. A
TMDB rating must retain its TMDB provenance, never fill a Douban/IMDb/RT slot.

The caller supplies a token resolver for its explicitly selected application
read token. Authorization and per-connection quota admission belong to the job
service. Credentials are sent in the Authorization header, not URL parameters,
documents or errors. Requests are restricted to the official API origin and do
not follow redirects. The transport performs no automatic retries or negative
caching; the scheduler owns retry budgets and cancellation.

Responses are limited to 4 MiB after the standard HTTP transport's decompression.
Search/find candidates have bounded counts. Successful empty results are distinct
from missing configuration, provider authentication failure, entity-not-found,
rate limiting, network/server failure, malformed data and oversized data.
`Retry-After` is returned as an absolute time for scheduler use. Diagnostic errors
exclude upstream bodies, request URLs and tokens. Image URLs are server-importer
inputs; applications receive authorized Fileway artwork assets after import.

## Remaining integration

This transport slice does **not** expose a public provider endpoint or declare
online scraping enabled. Library policies, credential storage/use grants,
recoverable metadata jobs, match-revision checks, field projection, attribution,
image fetching/cache and episode-order groups remain separate required work.
The default local-only library policy must make no provider calls. Missing
credentials do not block local NFO scanning and content browsing.

Tests use synthetic responses and credentials with an injected transport. They
do not establish project approval, current terms compliance, live API success or
real artwork delivery. Actual authorized configuration and provider acceptance
are recorded separately; no shared project token ships in the application.

Primary interface references: [movie search](https://developer.themoviedb.org/reference/search-movie),
[TV search](https://developer.themoviedb.org/reference/search-tv),
[movie details](https://developer.themoviedb.org/reference/movie-details),
[series details](https://developer.themoviedb.org/reference/tv-series-details),
[season details](https://developer.themoviedb.org/reference/tv-season-details),
[external ID lookup](https://developer.themoviedb.org/reference/find-by-id),
[configuration](https://developer.themoviedb.org/reference/configuration-details).
