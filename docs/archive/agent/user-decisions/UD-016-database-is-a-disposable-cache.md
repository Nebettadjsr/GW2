# UD-016 — The PostgreSQL database is a disposable cache

## Status

RESOLVED

## Decision Needed

How much protection, preservation, backup and reconciliation effort does data in the application's
PostgreSQL database deserve? STORY-INFRA-004 and AR-010 assumed it was valuable legacy data to be
archived, reconciled and recoverable from backup.

## User Decision

RESOLVED — Product Owner decision.

**The database is a convenience cache, not a store of record.** Everything in it is fetched from the
Guild Wars 2 API (account data, characters, inventories, material storage, recipes, Luck, Trading Post
prices, item metadata). Nothing in it is secret, irreplaceable or worth protecting. If a row is wrong,
missing, inconsistent or lost, the correct response is to fetch it again from the GW2 API.

Consequences, binding for every role:

1. No backups, restore procedures, archives, quarantine schemas, provenance ledgers or
   reconciliation manifests are required for database content. Do not design, implement, test or
   document them.
2. Data loss is acceptable and never a reason to block a story, refuse a cutover, or escalate to the
   Product Owner. Do not raise "what if this data cannot be recovered" questions.
3. On a schema change, an unrecognized/drifted legacy schema, or inconsistent cached data, the simple
   answer is to reset: drop and recreate the affected cached tables (or the whole schema) and
   re-synchronize from the GW2 API. Migrations may therefore be destructive for cached data.
4. Ownership of old unscoped rows never has to be proven. Old account rows may simply be discarded
   and refreshed by the first verified account synchronization.
5. Tests need to prove that a reset followed by a fresh synchronization yields correct, correctly
   scoped data. They need not prove that any pre-existing cached row survived.
6. This does not relax correctness of *current* behavior: account isolation, scoping and
   transactional publication of fresh data still apply. Only preservation of old data is dropped.
7. API-key handling is governed separately by UD-018 and TARGET_ARCHITECTURE section 8.2. The former blanket ban on browser key storage is superseded; do not infer high-sensitivity credential requirements from this cache decision.

## Blocks

Nothing. It resolves the open question behind UI-007 (STORY-INFRA-004) and removes the
preservation requirements of AR-010 / ADR-010 / TARGET_ARCHITECTURE sections 7.3-7.4.
