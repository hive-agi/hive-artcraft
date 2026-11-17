# Changelog

## Unreleased

### Fixes

- filmcraft: the socket transport now sends the top-level `"token"` FilmCraft's control port requires on every request. It is read per request from `:token-ref` (default env `FILMCRAFT_CONTROL_TOKEN`) and never logged or returned. Doctor reports a missing token with the fix. Apps without a token-ref are unchanged on the wire.

### Features

- One IAddon `hive.artcraft` with a single `artcraft` tool; app-module contract (malli) with `:port`, `:control-file` and `:none` transport shapes; data-list registry; photocraft and vectorcraft mounted from their published libraries.
