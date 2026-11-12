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
