# Operation history selection API

`POST /api/history/delete` accepts `application/json` with `{"ids":["entry-id"]}` and returns `{"deleted":1}`. The request must contain 1–500 IDs, each nonblank and at most 200 bytes. Unknown fields, multiple JSON objects and malformed IDs are rejected with 400. Duplicate IDs are deleted once; missing or foreign IDs do not contribute to the count.

Both handler and storage scope deletion to the authenticated user. Administrators cannot remove another user's operation history through this route. Files and task records are unchanged. The normal authenticated API and sliding-session headers apply.

`DELETE /api/history` remains the existing whole-current-user clear operation; it is unchanged. Clients must never append an IDs parameter to that route to emulate selected deletion: older servers ignore it and would clear all history. The separate POST route safely returns 404 on older servers. Android presents an upgrade-required message and never falls back to clear-all.

A successful count acknowledges the removal; a later list-read failure does not revoke success. When the response is lost or malformed, clients retain a recoverable unknown-result error, refresh to reconcile and do not automatically replay the mutation. Full-list selection follows all pagination cursors for the current filter and account before enabling the selection; repeated cursors fail rather than pretending a partial page is all results.
