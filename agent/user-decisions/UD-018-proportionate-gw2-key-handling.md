# UD-018 — Proportionate handling of read-only GW2 API keys

## Status

RESOLVED — Product Owner decision, 2026-10-25.

## User Decision

Read-only GW2 API keys are low-risk game-data access tokens in this product. Their purpose is to identify the GW2 account, separate users' data and make ArenaNet API calls. The Product Owner explicitly rejects treating them as highly sensitive financial credentials and prioritizes restoring a usable app. The app does not introduce payment or sensitive personal-data handling. This records the accepted risk; it does not assert that keys cannot reveal account/game information.

The PostgreSQL database remains a disposable, re-fetchable cache under UD-016.

## Consequences

1. Preserve verified account GUID scoping, permissions and correct account switching. Never mix users' inputs, cached results or task state.
2. Ordinary masked entry, session retention and disconnect are sufficient. Browser sessionStorage is the selected simple implementation for reload persistence; no encryption vault, separate application identity system or memory-only/re-enter-on-reload rule is required.
3. Keep basic hygiene: backend-owned ArenaNet calls, HTTPS in deployment, no deliberate key logging or URL exposure, and no keys in public task payloads. Browser key retention is an explicit exception to the general backend-only secrets rule.
4. Do not add security infrastructure, speculative threat scenarios or exhaustive tab/suspension/secure-erasure testing as prerequisites for app recovery. Verify ordinary use and account separation with focused tests.
5. Working backend checks may remain. Do not spend recovery time removing them solely to simplify their internals. Their exact lease duration and advanced lifecycle details are engineering choices, not new Product Owner requirements.

## Supersession

This decision supersedes ADR-009's mandatory memory-only storage choice, the former TARGET_ARCHITECTURE section 8.2 lifecycle rules, and UD-016 clause 7's blanket ban on API-key storage. It takes precedence over conflicting historical story/QA/architecture-request text. The live browser issue #13 must use this policy. TARGET_ARCHITECTURE sections 8–8.2 own the resulting technical contract.

## Source

Direct Product Owner correction in this session on 2026-10-25: keys are read-only, needed for user separation and ArenaNet requests; high-level security is unnecessary, and the re-fetchable database is not valuable persistent data.
