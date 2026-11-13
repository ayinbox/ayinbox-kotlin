# Changelog

## 0.1.0 (unreleased)

- First release: `me`, `campaigns` (list, get, create, pause), `contacts` (list, get, create, update, delete), `mailboxes.list`, `unibox` (listThreads, getThread).
- Generic `request`, `requestRaw` and `paginate` for every other route.
- `Flow` auto-pagers, typed exceptions, retries with backoff that honour `Retry-After`, per-call idempotency keys.
- Webhook signature verification with a constant-time compare.
