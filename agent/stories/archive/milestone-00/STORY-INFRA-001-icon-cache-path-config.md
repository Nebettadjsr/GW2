## Story ID

STORY-INFRA-001

## Title

Move the hardcoded icon-cache filesystem path into configuration

## Status

DONE

## Milestone

milestone-00

## Goal

Replace the Windows-specific, machine-specific absolute path hardcoded in `InitialSetupService.firstFill()` with a configurable value, using the same environment-variable-plus-`.env`-fallback pattern `repo.EnvConfig`/`repo.AppConfig` already established for the GW2 API key and database credentials, per `docs/KNOWN_PROBLEMS.md` §7.3's own recommendation ("move to configuration once configuration handling is introduced — likely the same piece of work").

## Authoritative Source Documents / Sections

- `docs/KNOWN_PROBLEMS.md` §7.3 (the confirmed observed fact and recommendation).
- `docs/TARGET_ARCHITECTURE.md` §17 (containerization needs this off any one developer's machine) and §15 (configuration direction).
- `CLAUDE.md` § Project-Specific Security Policy (configuration via environment variables, gitignored `.env` fallback — this is the established pattern to reuse, not a new decision).
- `src/main/java/InitialSetupService.java` (the hardcoded call site, line 26).
- `src/main/java/repo/EnvConfig.java`, `src/main/java/repo/AppConfig.java` (the existing pattern to extend).
- `.env.example`.

## Context

`InitialSetupService.firstFill()` calls:

```java
IconSync.syncItemIconsToDisk(Path.of("C:\\Users\\Administrator\\AppData\\Local\\NebetGw2Tool\\icons"));
```

This breaks on any machine other than the one it was written on, and blocks containerization as-is. `repo.EnvConfig` already exists and provides `require(key)` (environment variable, falling back to a gitignored `.env` file). This value is not a secret (unlike the API key/DB password) and should not be *required* to be set — it needs a sensible, portable default (e.g., resolved from `System.getProperty("user.home")`) when no override is configured, since forcing every developer to set an env var just to pick a cache directory would be a regression in usability, not an improvement.

## Acceptance Criteria

- The icon-cache directory is no longer a hardcoded absolute path in source.
- An optional environment variable (e.g. `ICON_CACHE_DIR`, documented in `.env.example`) can override the location; when unset, a portable, cross-platform-sensible default is used instead of the current Windows-admin-specific path.
- `repo.EnvConfig` gains an optional-lookup capability (a variant of `require(...)` that returns `null`/an `Optional`/a caller-supplied default instead of throwing) if one does not already exist — reuse `require(...)`'s existing `.env`-fallback logic rather than duplicating it.
- `InitialSetupService.firstFill()` uses the new configuration value instead of the hardcoded `Path.of(...)` call.
- No other behavior of `firstFill()` changes.

## Required Tests

- None strictly required for this config-plumbing change (no existing test exercises `InitialSetupService`, and adding live-filesystem/live-sync test coverage is out of scope here per `docs/TEST_STRATEGY.md`'s current no-live-external-dependency rule). If a cheap, no-I/O unit test of the new `EnvConfig` optional-lookup method is easy to add without touching `.env`/filesystem state, add it; otherwise state "None" in the result and explain why.
- `./mvnw test` — full existing suite must continue to pass unchanged (this change should not affect any of it).

## Constraints

- Do not change `IconSync.syncItemIconsToDisk`'s behavior itself, only what path it is called with.
- Do not introduce a new configuration mechanism — extend `repo.EnvConfig`/`repo.AppConfig`'s existing pattern.
- Do not require the user to set the new environment variable for the application to keep working (it must have a working default).
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] Hardcoded path removed from `InitialSetupService.java`.
- [x] New optional environment variable documented in `.env.example`, with a portable default when unset.
- [x] `./mvnw test` shows unchanged pass/fail results (plus any small new config test, if added).
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

`InitialSetupService.firstFill()` no longer hardcodes
`C:\Users\Administrator\AppData\Local\NebetGw2Tool\icons`. `repo.EnvConfig` gained an `optional(key,
defaultValue)` method that shares `require(...)`'s environment-variable/`.env`-fallback lookup via a
new private `lookup(key)` helper returning `Optional<String>` (no duplicated lookup logic). `repo.AppConfig`
exposes `ICON_CACHE_DIR = EnvConfig.optional("ICON_CACHE_DIR", <user.home>/.nebet-gw2-tool/icons)`, and
`InitialSetupService` now calls `IconSync.syncItemIconsToDisk(Path.of(AppConfig.ICON_CACHE_DIR))`.
`ICON_CACHE_DIR` is documented as optional in `.env.example`. Added a small no-I/O unit test,
`repo.EnvConfigTest`, asserting `optional(...)` returns the caller-supplied default for an unset key.
`./mvnw -DskipITs clean test`: 10 tests run (previous 9 + this new one), 0 failures, 0 errors, `BUILD
SUCCESS`. No other behavior of `firstFill()` or `IconSync.syncItemIconsToDisk` changed.

## Blockers

None.
