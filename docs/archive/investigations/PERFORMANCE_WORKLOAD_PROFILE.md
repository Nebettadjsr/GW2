> **Historical, programme ended, not a launch requirement.** The capacity/resource programme was retired by STORY-LAUNCH-001; commands named below no longer exist and results are not passed gates.

# Workload-profile note for capacity discovery (STORY-KPI-007 → STORY-KPI-002 / #184)

Source-based input for the later discovery driver. It measures nothing and sets no target. **No measured
app-specific average cadence exists.** Four kinds of statement are kept apart below.

## 1. Product Owner–provided workflow intent (not measured)

- Crafting: a plan is built in a burst of Crafting Profit requests, then the player spends minutes acting in game.
- Bank, Materials and Characters: browsing with dwell time between page visits.

## 2. Observed implementation (code at this revision)

- Session lease is absolute 15 minutes, never extended by use: `AccountSessions.LEASE`
  (`backend/src/main/java/application/access/AccountSessions.java:38`); credentials are proven once per session, not per request.
- Crafting recovery polling: 1 s interval inside a 5 min budget — `RECOVERY_POLL_INTERVAL_MS`, `RECOVERY_BUDGET_MS`
  (`frontend/src/crafting/useCraftingProfit.ts:32-35`). A stale result therefore causes a poll *burst*, not steady load.
- Account data freshness: the backend default account max age is 15 min (`gw2.account-data.max-age-ms:900000`,
  `backend/src/main/java/web/Gw2ApiApplication.java:115`; `CachedCraftingProfitService.DEFAULT_ACCOUNT_MAX_AGE`, `:50`).
  The browser schedules an automatic account refresh from the oldest source timestamp plus that max age (+1 s), 30 s when
  already expired, 60 s when unknown, with failure backoff 60 s–300 s, a 300 s bound per refresh, and nothing while the tab
  is hidden (`frontend/src/account/useAutomaticAccountRefresh.ts:54-64,99`). Visible tabs therefore re-sync about every 15 min
  of use; hidden tabs generate no refresh traffic.
- Account task status polling in the shell: `STATUS_POLL_INTERVAL_MS = 3_000` while a task is running
  (`frontend/src/sync/useSyncOperations.ts:23,195`).
- System Status screen: while mounted it polls `loadSystemStatus()` every 5 s, and owner details every 5 s for an owner or
  while owner status is unreachable; non-owners are discovered once per generation (`frontend/src/sync/SyncScreen.vue:143-147`).
- Client-local interaction (checkbox, search, sort) on already-loaded lists is computed in the browser by the screen
  components; it is not shown here to issue backend requests. Whether any particular control does so must be confirmed per
  journey from the screen source; this note does not assign it a request rate.
- Ingredient search is debounced client-side (`frontend/src/crafting/useIngredientSearch.ts:23`).
- Global data refresh runs on a fixed delay (default 6 h, 60 s initial) — `GlobalDataRefreshScheduler` (`:20-21`); it is
  background load independent of browsing.
- Request fan-out per page is defined by the per-screen API modules under `frontend/src/api/` (`craftingApi.ts`,
  `charactersApi.ts`, `accountApi.ts`, `syncApi.ts`, `systemApi.ts`); the driver must derive the exact request set per
  journey from those modules rather than from this note.
- PostgreSQL is a disposable cache (UD-016); reads are scoped per account (`model.AccountScope`).

## 3. External methodology (not adopted tooling)

- Breakpoint testing — gradual limit discovery, degradation/errors, repeatability: https://grafana.com/docs/k6/latest/testing-guides/test-types/breakpoint-testing/
- Website load testing — parallel fan-out, cookies/cache, varied think time, hybrid browser + protocol: https://grafana.com/docs/k6/latest/testing-guides/load-testing-websites/
- Open vs closed models — a closed loop slows down and can reduce offered load; report scheduled vs achieved traffic: https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/
- Task analysis — duration/frequency needs contextual observation; scripted behaviour is not a measured average: https://www.nngroup.com/articles/task-analysis/
- Context only: Pfau & Seif El-Nasr, CHI 2023, DOI 10.1145/3544548.3581404 report mean 3m51s–8m08s dwell on GW2 Wingman pages.
  A different tool and audience (2023, analytics/raiding): it shows multi-minute dwell is plausible, nothing more. It does not validate a click rate or HTTP frequency.

## 4. Provisional, configurable sensitivity ranges (assumptions, not averages or thresholds)

| Journey | Provisional range for the driver to vary | Status |
| --- | --- | --- |
| Bank / Materials / Characters browsing | dwell between page visits 30 s – 5 min | assumption |
| Crafting plan burst | 5–30 profit requests in under 1 min, then 2–10 min idle | assumption |
| Crafting stale-recovery polling | 1 s (from code), up to 5 min | observed implementation |

The driver should report every browser sample over 2000 ms, distributions and maxima, first breach and repeatability per
journey/profile, plus scheduled versus achieved traffic. No fixed user count and no 3–5 s fallback.
