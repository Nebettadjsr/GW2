## Status

RESOLVED

## Decision Needed

Does the Crafting Profit seven-second navigation-to-complete-page budget include on-demand refresh of missing or expired Trading Post prices? If excluded, specify the timing boundary and what still must meet the budget. If included, confirm this state must meet the same complete-page requirement.

## Why This Is Needed

STORY-PERF-003 observed 17,206 ms with expired prices versus a maximum of 4,128 ms across its fresh-price cache states. This decision defines acceptance scope; it does not accept performance or select a technical remedy.

## Context

docs/TARGET_ARCHITECTURE.md section 9 requires on-demand shared TP refresh, reusing quotes younger than ten minutes; section 17 sets the complete-page budget. docs/TEST_STRATEGY.md section 34 prohibits selecting only favorable cache states. STORY-PERF-003 F001 records 2,984 upstream fetches. Targeted inspection of CraftingProfitApiController confirms refreshForProfit still precedes calculation. UD-014 asks about account synchronization, a distinct policy and wait; UD-013 asks about explicit acceptance. Neither answers this price-refresh case.

## Blocks

Final disposition of STORY-PERF-003 F001, expired-price performance acceptance and Phase 5 closure. Scope any required remediation/verification after the answer; route an unsettled architectural remedy to the architect then. Does not block STORY-WEB-033's correctness repair.

## External Input Possibly Required

None; current measurements and refresh policy are in the cited artifacts.

## User Decision

RESOLVED

## Resolution

## Product Owner Decision — STORY-WEB-033

**Decision: Use stale-while-revalidate for Trading Post prices.- Serve cached data while refreshing (stale-while-revalidate).**

The seven-second Crafting Profit navigation-to-usable-page budget includes the initial calculation using available cached TP prices, but excludes the subsequent asynchronous refresh and recalculation.

Required behavior:

1. Return available cached account data immediately, even when its freshness TTL has expired. Continue refreshing the required sources asynchronously.
2. Run crafting profit calculations immediately using the cached account data. Calculation performance is currently fast enough that recalculating after synchronization is acceptable.
3. Once the required account sources have been refreshed successfully, automatically recalculate with the updated data and replace the displayed results. Preserve the user's current settings, filters, selections and sorting.
4. while the above steps are in progress: temporarly Reuse the existing TP-price warning visual component/design for a new status indicator: **"Stale data – refreshing…"**. Instead of the red outline, use a green animated border with a continuously cycling loading effect. Explain that cached results are displayed until the refresh completes.
5. Remove the refreshing indicator only when the required account data is confirmed fresh and the new calculation has completed. Do not treat task completion alone as proof that all required sources are fresh.
6. If synchronization fails or times out, retain the cached results, stop the loading animation, and display an appropriate failure status with a retry action. Never leave the UI indefinitely loading.
7. If no usable cached data exists, retain a loading/empty state until the required data is available. Do not fabricate account data.
8. Keep existing source-specific freshness tracking and account-sync concurrency guarantees. Fix the overlapping refresh scope defect described in STORY-WEB-033, including cases where additional synchronization tasks are required.

**Important:** Cached calculations are provisional. Display them as such while refreshing. Preserve the existing profit mathematics, including material valuation and applicable Trading Post fees.

The asynchronous refresh and recalculation are excluded from the initial seven-second budget but require separate performance measurements and bounded recovery behavior.

Update the performance acceptance criteria and architecture documentation accordingly. Preserve existing profit calculation mathematics and Trading Post fee handling.

This decision concerns TP prices. Apply the same general stale-while-revalidate user experience already selected for account data, while retaining their separate refresh mechanisms.