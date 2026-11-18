# GW2 roadmap

Updated 17 November 2026. Product intent belongs to the Product Owner. GitHub issues and pull requests own execution status; [the supervisor backlog](../agent/BACKLOG.md) records authorization and the next handoff. See [Development workflow](DEVELOPMENT_WORKFLOW.md) for the implemented process.

## Delivered baseline

The application is publicly available on Oracle at `https://gw2-crafting.de/welcome`. The single-host frontend, backend and PostgreSQL stack, HTTPS, account isolation, legal pages and owner activity downloads are deployed. The active UI includes Crafting Profit, Crafting Discovery, Ectoplasm Salvage, Bank, Materials and Characters with equipment, inventory and build views.

Bank and Materials protection includes immediate saving, whole-tile selection, finite reservations, independent Bank-stack allocation, readable reservation notes, names and the shortcut to ingredient search. The owner has accepted the shipped correction. Detailed product behavior belongs in [DOMAIN_SPEC](DOMAIN_SPEC.md) and [UX guidelines](FRONTEND_UX_GUIDELINES.md), rather than a second feature contract here.

The observed deployed revision and runtime evidence belong in [Current state](CURRENT_STATE_SPEC.md); [Architecture](ARCHITECTURE.md) defines boundaries and the remaining host configuration dependency.

## Current authorized work

The owner authorized documentation cleanup and reconciliation, followed by useful maintenance
and meaningful coverage work on 17 November 2026. The cleanup is merged in #241 and account
HTTP/session coverage #111 is delivered through #242. These completions do not imply another
production deployment.

The next authorized slice is [#243, STORY-COV-006](https://github.com/Nebettadjsr/GW2/issues/243):
curated real-browser desktop journeys, the owner-confirmed
[viewport policy](DOMAIN_SPEC.md#supported-browser-viewport), removal of unsupported phone-tool
test repetitions and maintained process/testing documentation. Reuse the production build and
existing Playwright-core/controlled-origin helpers. The implementation branch provides
`npm run smoke:ci` (`node scripts/run-desktop-journeys.mjs`) after build in the required
frontend CI job. The owner revised the local proposal on 18 November to a nonblocking notice
and horizontal desktop canvas: initial narrow tools load normally, and width does not hide
content or close modals. Source `1d017fc` and independent QA implement this revision; QA
replaced superseded assertions while preserving the user stories.
Consult the PR for current verification; local owner acceptance and final-head CI/review are
required before merging. It is not deployed.

Independent QA owns tests, fixtures/helpers and explained mobile-test removals for this session;
Claude implements the released source/package/CI scope. Preserve desktop feature assertions,
native keyboard/focus, short-height and zoom accessibility. Report named browser journeys
separately from line/branch coverage; keep the 90% code-coverage target informational. No live
user keys, capacity workloads or hosted test service are required.

Default CI, current-head native review and issue-specific local evidence remain required.
The new notice must be available in an isolated local app for concrete owner visual approval;
#243 remains held with `pairing` until that acceptance. Oracle deployment is separate.
Continue reconciling [known problems](KNOWN_PROBLEMS.md) against current code/merged evidence;
remove resolved findings and retain concrete future performance work.

## Prepared maintenance candidates

| Issue | Remaining scope |
| --- | --- |
| [#243](https://github.com/Nebettadjsr/GW2/issues/243) | Authorized desktop-browser journeys and viewport support notice; held for scoped QA and local UI acceptance. |
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
