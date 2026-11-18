# GW2 Tool — Current State

## Purpose and authority

Source/runtime summary reconciled on 2026-11-17. [DOMAIN_SPEC](DOMAIN_SPEC.md) owns product
rules and [ARCHITECTURE](ARCHITECTURE.md) owns boundaries and selected constraints.
The owner and approved GitHub issues determine priorities; PRs/CI hold execution evidence.
A source description or past deployment check does not certify every feature or future release.

## Runtime and local development

| Component | Current implementation |
| --- | --- |
| Browser | Vue 3 / TypeScript / Vite; the only product UI. |
| Backend | Java 25 / Maven / Spring Boot 4.1.1; HTTP, calculations, persistence and ArenaNet access. |
| Database | PostgreSQL; shared game/economy cache and GUID-scoped account cache. |
| Containers | Static frontend Nginx, non-root Java backend, PostgreSQL 17. |
| Account access | Browser-held read-only key, verified GUID and bounded sessions; no separate app login. |

Profit/Discovery and character attributes are backend results. Ectoplasm Salvage is the
documented browser calculation exception. The frontend makes no direct ArenaNet calls.

Start from `backend/` with `./mvnw spring-boot:run` (Windows: `.\mvnw.cmd spring-boot:run`).
`repo.EnvConfig` reads the working-directory `.env`, or the repository root's when started from
`backend/` without one there. Database settings are in [`.env.example`](../.env.example).
`GW2_API_PORT` defaults to 8080. Flyway validates/applies the schema before Spring starts.

From `frontend/`, run `npm ci` then `npm run dev`. Vite defaults to 5173, proxies `/api` and
preserves browser Host. `GW2_FRONTEND_PORT`/`GW2_BACKEND_ORIGIN` allow alternate ports.
The local three-container stack starts with `docker compose up --build --wait` from `docker/`
at `http://127.0.0.1:8088`; see [Docker setup](../docker/README.md).

Enter the key on System Status. `sessionStorage` retains it across navigation/reload; restoration
proves it before account reads. Disconnect/invalid-key rejection removes it. Browser workflows
need no server `GW2_API_KEY`; scoped routes never use it as fallback. Reservation preferences
are secret-free, per-verified-GUID `localStorage`.

Owner controls use `GW2_OWNER_ACCOUNT_GUID` in the backend **process environment** or Spring
property `gw2.owner.account-guid`. Blank grants nobody; malformed values fail startup.
Putting it only in `repo.EnvConfig`'s `.env` does not inject it into Spring's environment.
Both Compose files explicitly forward it. Restart the backend after changing owner settings.

## Public Oracle deployment

The live application is **`a2490cda6f988b0769468fe4dcf80359d4678fef`**, published after the
owner's approval in the visible Oracle terminal on 2026-11-16 from
[#236](https://github.com/Nebettadjsr/GW2/pull/236). Its tree exactly matches reviewed/CI
candidate `61a10cfa1686f7763696c9a725cf809a0792644c`.
Later documentation-only changes do not imply another application deployment.

| Runtime fact | Last verified deployment |
| --- | --- |
| Public entry | `https://gw2-crafting.de/welcome` |
| Host | Oracle Frankfurt AD 2, Ubuntu 24.04 ARM64, 2 OCPUs / 12 GB RAM. |
| Address | `130.61.78.114` |
| Stack | Docker 29.9.3 / Compose 5.7.1; frontend/backend/PostgreSQL 17. |
| Published port | Frontend `127.0.0.1:8088`; backend and DB publish none. |
| Active release | `/opt/gw2/current` → `/opt/gw2/releases/a2490cda6f988b0769468fe4dcf80359d4678fef` |
| Update effect | Backend/frontend replaced and healthy; PostgreSQL container unchanged. |

### Required host configuration

Every host Compose command includes the release's `docker/compose.production.yaml`, root-owned
`/opt/gw2/config/https.compose.yaml`, and explicit root-owned mode-0600
`/opt/gw2/config/production.env`. The override supplies exact
`GW2_ACCOUNT_ACCESS_ALLOWED_ORIGINS=https://gw2-crafting.de` and the separate restricted
`gw2_app` DB credential. The production file alone is insufficient for this host;
[#228](https://github.com/Nebettadjsr/GW2/issues/228) tracks portability.
Secrets and operator configuration stay in ignored local/protected host files.

Host Nginx 1.24 and Certbot 2.9 terminate TLS. HTTP/www redirect to canonical apex HTTPS;
unknown TLS names are rejected. Recorded certificate expiry is 2027-02-13; the renewal timer
and successful-renewal validated Nginx reload hook passed a dry run. HSTS preloading is off.
Public access is enabled; the former owner-source allowlist was removed.

The edge preserves Host, strips client forwarding headers and requires exact canonical HTTPS
Origin for unsafe methods. Missing, foreign and plaintext Origins returned 403. A real owner
proof through ordinary DNS passed: proof 200, owner details 200, logout 204, subsequent owner
read 401. The cookie was Secure, HttpOnly, SameSite=Strict and host-only. Verification artifacts
contain no key, cookie or raw access context.

The actual `gw2_app` role has required schema/Flyway rights but no superuser/create-role/
create-database/replication/bypass-RLS flags. The bootstrap role's network password is disabled.
The frontend has no DB credential. PostgreSQL uses SCRAM on the private Docker network;
internal DB TLS is off. The inspected host uses key-only SSH; no privileged container,
Docker-socket mount or Docker TCP listener was observed. These are bounded inspected facts,
not an exhaustive security or multi-account audit.

### Public pages, indexing and processing

Welcome contains the introductory/tool/getting-started/beginner tabs and local compressed,
responsive WebP screenshots. Tool bookmarks retain `/#/...` URLs.
Public Home and footer information pages never restore a key or request account data.

Vite builds `index.html` and `welcome.html`. Only successful canonical `/welcome` responses
(200/206/304) permit `index, follow`. Errors, redirects, tools, legal/privacy/storage, APIs and
assets remain `noindex, nofollow`. `/welcome.html` redirects to `/welcome`.
`robots.txt` allows `/welcome$` and `/assets/`, then denies other paths. No sitemap is published.
These directives guide cooperating crawlers, not access control or guaranteed search exclusion.

The approved Legal notice, Privacy and Cookies & browser storage pages contain the operator's
German contact facts, working `kontakt@gw2-crafting.de`, ArenaNet attribution and MIT/source/
feedback links. Actual operator facts are public build inputs; tracked examples keep placeholders.
No analytics/advertising or registration name/email/password feature exists. GUIDs, character
names and activity are account-linked, not anonymous; network delivery also processes IPs.
Copy approval is not a claim of legal certification or completed provider agreements.

Sparse owner activity CSVs retain at most 30 **nonempty** UTC days, 2 MiB/day and 32 MiB total.
Idle time writes no empty history; day/week selection/download is owner-authorized. Host CPU/RAM
stays in Oracle Monitoring. Docker logs default to three 10 MB files/container. Query-free edge
logs contain timestamp/method/path/status/size/time without keys or client IPs; request-bound raw
error logs are suppressed. Edge logrotate keeps three rotations, checked daily at a 10 MB
threshold; Certbot keeps five rotated logs plus current. These are size/count bounds, not a
universal time-based deletion guarantee.

### Verification evidence and limits

Default CI runs backend/frontend suites, isolated PostgreSQL checks and controlled browser
journeys. Commands and scope are in [Test strategy](TEST_STRATEGY.md); exact results and
release checks stay in GitHub/CI. Synthetic checks do not establish live upstream
availability, exhaustive multi-account validation or production capacity.

## Current workflows

| Area | Implemented behavior |
| --- | --- |
| Profit | Backend cached-input calculation, provisional results, bounded recovery, selected-row tree and shopping list. |
| Discovery | One character/discipline and one attempt per candidate; backend eligibility/resolution. |
| Ectoplasm Salvage | Browser expected-yield/Luck calculation from backend quotes and account facts. |
| Bank / Materials | Whole-tile toggle, autosave, finite reservations, right-click actions and cached item names. |
| Characters | Lazy selected-character reads, roster sorts, bag layouts, equipment and read-only saved Builds tabs. |
| System Status | Connection, own-account sources/full refresh and generic health; owner catalogs/activity/downloads. |

Bank and Materials preferences are separate. Bank stack selection is independent; numeric targets
distribute in ascending slot order and preserve the saved item amount after rearrangement.
Finite targets have solid yellow borders and readable notes beside their tab/category grid,
below on narrow layouts. Backend protection applies before aggregation and preserves character
stock. There is no Save/Discard/pending-change workflow. Exact rules are in DOMAIN_SPEC section
9.1 and FRONTEND_UX_GUIDELINES section 2.1. Mystic Forge remains a disabled placeholder.

Bank/Materials/Characters retain last-good content while their own sources refresh.
Automatic refresh follows backend age guidance, pauses hidden/inactive views and backs off
after failure. Optional equipment/build tabs cover a published roster generation. Ordinary
reads do not fetch every character detail or repair metadata upstream. Character attributes are
the documented level-80 land-equipment/supported-passive projection, not a full combat simulator.

Profit's **Refresh data & results** resubmits the calculation; the backend alone admits needed
account/TP work. Inputs are Bank, Materials, account recipes and Characters, excluding Luck.
Last-good stale inputs yield provisional 200; absent required publications yield pending 202
without rows. The browser follows advertised tasks and recalculates within one five-minute
deadline, retaining settings/last-good rows on failure. Detail uses its own compatible input
generation; completed matching detail is reused after unchanged return navigation.

Both calculators reuse the shared on-demand ten-minute TP cache. Indirect ingredient search is
a debounced recipe-ID lookup, intersected with returned rows; it neither calculates nor fetches
prices. Checked arithmetic and bounded material expansion/simulation prevent the former wrapped
cost/excessive-expansion bug. DOMAIN_SPEC owns economic semantics and exact numeric limits.

Global metadata checks run at a six-hour fixed delay with one-minute initial delay. Changed
recipe data is persisted before graph rebuilding; incomplete publication remains retryable.
Unchanged ID lists do not detect in-place recipe edits.
Account writers are serialized per GUID. Sessions, global/TP coordination, task history and global
check/error timestamps are process-local; persisted source generations remain freshness authority.

### Account data deletion

**Delete cached data** confirms then calls `DELETE /api/account/data` for the verified GUID.
Success is 204; unfinished refresh returns 409 `ACCOUNT_WORK_IN_PROGRESS`; failure to proceed
safely returns 503 `DATA_STORE_UNAVAILABLE`. Committed deletion invalidates that account's
sessions (401 `ACCOUNT_DATA_DELETED`) and clears its browser reservation/connection state across
same-origin tabs. Other accounts and shared game data remain. Disconnect only removes connection/
key. Explicit Connect can repopulate cached account data.

An expired/displaced context never automatically retries DELETE: confirmation is required again
and the next explicit attempt rechecks the retained key. Network failure alone cannot establish
whether deletion committed. Late replies cannot disconnect a newer explicit connection.

## Persistence and generated files

| Scope | Main records |
| --- | --- |
| Shared game/economy | Items, recipes/ingredients/publication, ordered materials, TP-tradeable IDs and quotes. |
| Account | Registry/activity, per-source generations, bank, material quantities, recipes and Luck. |
| Character | Roster/crafting/recipes/items/bags, bag publication, equipment/build tabs and coverage. |
| Shared character references | Equipment details/stats and specialization/trait/skill/pet/legend metadata. |
| Filesystem | Derived graph, downloaded images and bounded activity CSV/index files. |

Game-data/graph/image caches can rebuild. Source generations distinguish missing from
successfully empty data; publication is transactional per source. There is no server preference
store, GW2 key table, durable session store or multi-replica coordination.

## Build, verification and development workflow

From `backend/`, run `./mvnw test` and `./mvnw test jacoco:report`.
From `frontend/`, run `npm test`, `npm run test:coverage`, `npm run type-check`, `npm run build`.
CI runs default Maven/Vitest and publishes JaCoCo/V8 plus combined coverage; the 90% target is
warning-only. Real PostgreSQL, browser and live API checks need explicit evidence under
[TEST_STRATEGY](TEST_STRATEGY.md).

GitHub issues, branches, PRs, CI and native Codex review are the execution state.
The current process and implemented automation are in [agent/README](../agent/README.md).
Historical records are in [docs/archive](archive/README.md) and the `pre-github-flow` tag;
they do not activate work or impose retired test-ownership stages.

## Remaining limits

- One active account per shared-cookie browser session; independent accounts in such tabs are
  not promised. Backend GUID separation remains enforced.
- Public production configuration depends on the protected host override (#228).
- Multiple backend replicas require an explicit coordination/session design.
- In-place recipe edits under unchanged ID sets are not automatically detected.
- Character projections cover documented equipment/passive effects only.
- No supported-user capacity is claimed. The withdrawn pre-release capacity programme is not a
  launch gate; existing activity data supports real-user follow-up.

Open defects are in [KNOWN_PROBLEMS](KNOWN_PROBLEMS.md) and GitHub. Deferred architecture choices
are in [ARCHITECTURE](ARCHITECTURE.md).
