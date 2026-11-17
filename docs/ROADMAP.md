# GW2 roadmap

Updated 17 November 2026. Product intent belongs to the Product Owner. GitHub issues and pull requests own execution status; [the supervisor backlog](../agent/BACKLOG.md) records authorization and the next handoff. See [Development workflow](DEVELOPMENT_WORKFLOW.md) for the implemented process.

## Delivered baseline

The application is publicly available on Oracle at `https://gw2-crafting.de/welcome`. The single-host frontend, backend and PostgreSQL stack, HTTPS, account isolation, legal pages and owner activity downloads are deployed. The active UI includes Crafting Profit, Crafting Discovery, Ectoplasm Salvage, Bank, Materials and Characters with equipment, inventory and build views.

Bank and Materials protection includes immediate saving, whole-tile selection, finite reservations, independent Bank-stack allocation, readable reservation notes, names and the shortcut to ingredient search. The owner has accepted the shipped correction. Detailed product behavior belongs in [DOMAIN_SPEC](DOMAIN_SPEC.md) and [UX guidelines](FRONTEND_UX_GUIDELINES.md), rather than a second feature contract here.

The observed deployed revision and runtime evidence belong in [Current state](CURRENT_STATE_SPEC.md); [Architecture](ARCHITECTURE.md) defines boundaries and the remaining host configuration dependency.

## Current authorized work

The owner authorized documentation cleanup and reconciliation, followed by resuming useful maintenance and raising meaningful test coverage on 17 November 2026.

1. Archive retired records, consolidate architecture documentation, reconcile the domain specification, and document the actual agent/GitHub delivery process.
2. Refresh coverage from successful current CI artifacts and complete a bounded coverage story against active behavior. Keep the 90% line/branch target informational and preserve existing assertions.
3. Reconcile [known problems](KNOWN_PROBLEMS.md) against current code and merged evidence. Remove resolved findings; retain concrete functional and performance follow-ups that still apply.

Default CI, current-head native review and any issue-specific local checks remain required. New UI layouts need owner review in the local app. The documentation task does not authorize a feature redesign or an infrastructure service change.

## Prepared maintenance candidates

| Issue | Remaining scope |
| --- | --- |
| [#111](https://github.com/Nebettadjsr/GW2/issues/111) | Account HTTP route mapping, session recovery and refusal behavior coverage. |
| [#21](https://github.com/Nebettadjsr/GW2/issues/21) | Broader coverage improvements, selected from current package and branch gaps. |
| [#60](https://github.com/Nebettadjsr/GW2/issues/60) | Optional real-database fixture migration and account-task lookup semantics. |
| [#228](https://github.com/Nebettadjsr/GW2/issues/228) | Portable production configuration for the restricted database role and HTTPS origin. |

Consult the live issue before selecting it. Preserve the deployed protected Compose override until a reviewed replacement is proven. PostgreSQL remains a disposable GW2 cache, without backup or preservation work.

## Deferred Mystic Forge support

The owner approved future inclusion of Mystic Forge recipes, including intermediate steps, behind `Allow Mystic Forge recipes` below `Allow daily craft`. The current UI placeholder remains disabled. Forge recipes do not require a crafting discipline or crafting rating; list and detail calculations must eventually honor the same setting.

Recipe coverage, random-output handling and valuation policy still need a concrete contract. Do not invent those rules or activate implementation from the placeholder. The original [owner request](archive/agent/product-owner-requests/mystic-forge-crafting.md) is preserved as history; this section retains its pending scope.

## Concrete future optimization

Keep source-backed opportunities such as graph reuse, narrower root evaluation, large-list rendering, polling coalescence, cache recovery and upstream quote handling available for later prioritization. [Known problems](KNOWN_PROBLEMS.md) owns open findings; the [optimization report](OPTIMIZATION_INVESTIGATION_REPORT.md) identifies dated experiments and hypotheses. Historical timings are not current production guarantees.

## Retired programme

The owner ended the pre-release capacity/resource programme on 14 November 2026. Extra API-key searches, VM or container load tests, interaction recording/replay and the informational Performance workflow are retired. Issues #116, #154, #189 and #191 were withdrawn. Do not recreate that programme as a gate for maintenance or public hosting.

Use actual production activity and Oracle monitoring to observe the live service. No supported-user capacity is promised. Historical plans and measurements are in [the archive](archive/README.md).
