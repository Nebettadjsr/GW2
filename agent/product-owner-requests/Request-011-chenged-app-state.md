# Product Owner Request

## Status

RESOLVED

## Title

Update milestone-05 planning after clean runtime restart and add restart discipline to the permanent workflow

## Requested Change

A clean restart of the backend changed the observed application state significantly.

Several issues previously reported as product defects were caused by an outdated running backend rather than by the current source revision.

After restarting the backend, the Product Owner rechecked the application and confirmed that the following previously reported issues are now resolved:

- Real item icons are displayed.
- `Total sell value` is populated.
- The Crafting Resolution tree is displayed.
- `Allow non-Trading-Post materials` now remains selected and functions correctly.

The planner may therefore treat these four observed issues as resolved.

Existing stories or backlog entries whose only remaining purpose is to implement/fix one of these four symptoms should be closed, cancelled, removed from selectable work, or otherwise reconciled as appropriate rather than duplicated or kept alive unnecessarily.

This request does **not** resolve or alter the other still-open Crafting Profit presentation issues.

The following previously reported issues remain valid and must continue to be planned/implemented:

- Remove the redundant `Buying is off` label/message where it adds no useful information.
- Remove the unnecessary `FOR ONE FURTHER CRAFT` section when buying is enabled; the useful information is the purchase list for the already calculated craft count.
- Remove or replace the generic `Over the buy limit` label with useful concrete information where appropriate.
- `Price missing` must identify which item is missing a usable price.
- Remove the redundant `Not blocked` label.
- Review remaining status labels/messages for actual user value; labels should communicate useful information or be removed.
- Remove the introductory sentence:
  `Crafting opportunities the backend calculated for the selected scope, with the profit it reported for each.`

## Why / Product Intent

The previous observed state was misleading because the browser/frontend was communicating with an outdated backend process.

This caused current frontend/source behavior to appear broken even though the corresponding implementation already existed in the repository.

A clean backend restart immediately changed the visible application behavior and resolved several reported symptoms.

Planning and verification must therefore distinguish between:

`current source is defective`

and

`the running application is stale`

before creating additional implementation work.

The Product Owner should not have to rediscover stale runtime state manually after major implementation changes.

## Constraints

- Do not reopen or recreate implementation work for the four issues now confirmed working after restart.
- Do not mark the remaining presentation issues as resolved.
- Reconcile existing stories/backlog entries against the newly observed runtime state.
- Preserve unrelated completed and in-progress work.
- Do not use this request to declare milestone-05 complete while the remaining Product Owner issues are still open.

### Permanent workflow requirement

Add a durable workflow rule covering runtime freshness after substantial changes.

After significant backend/frontend changes, especially changes involving:

- API contracts,
- DTOs / response fields,
- routes/endpoints,
- backend/frontend integration,
- configuration affecting served behavior,
- or other changes where an already-running process may continue serving outdated code,

the workflow must ensure a clean runtime before relying on browser/integration observations.

The expected verification sequence is conceptually:

`build/current source -> stop stale runtime -> start current backend/frontend -> verify`

Where appropriate, both backend and frontend/dev-server processes should be restarted so that the runtime under test is known to represent the current source revision.

This requirement should be incorporated permanently into the relevant agent/workflow instructions, test strategy, lessons, or other authoritative process documentation rather than remaining only as a note in this request.

The purpose is not to force unnecessary restarts after every trivial edit. It is to prevent stale long-running processes from being mistaken for current implementation behavior after substantial integration changes.

## Additional Context

STORY-WEB-014 reproduced three of the reported failures against the old running backend and established that the process was serving an older/incompatible contract. Its source trace found the required current behavior already present and identified rebuilding/restarting the backend as the concrete runtime remedy. :contentReference[oaicite:0]{index=0}

After the backend was manually stopped and restarted, the Product Owner confirmed that:

- Total Sell Value works.
- Crafting Resolution works.
- The non-Trading-Post-material checkbox works.
- Item icons are now visible as intended.

These observations supersede the earlier Product Owner observations for those four points only.

The remaining UI/presentation cleanup requirements are unchanged.

## Planner Resolution

2026-09-27: Reconciled against the open milestone-05 queue. The four runtime symptoms
(icons visible, Total Sell Value, Crafting Resolution and the non-Trading-Post-material
control) are accepted as resolved after the clean backend restart recorded in
`agent/stories/STORY-WEB-014-profit-live-contract-repairs.md`; that story remains DONE.
No new implementation story was created and no completed icon or Profit feature story
was reopened. `STORY-WEB-015-profit-purchase-and-blocking-details.md` remains TODO because
its seven presentation requirements are explicitly still open. `STORY-SYNC-004-complete-
referenced-item-metadata.md` remains TODO because its scope is metadata coverage and
repair for referenced account/crafting items, not merely whether currently available
icons render. `STORY-DOM-023-crafting-profit-fee-integration.md`,
`STORY-DOM-024-ectoplasm-fee-policy-alignment.md`, `STORY-WEB-013-ectoplasm-salvage-
page.md` and `STORY-WEB-009-replaceable-favicon.md` are unaffected and remain TODO.

Added the permanent clean-runtime verification rule to `docs/TEST_STRATEGY.md` section
12.2 and the operational lesson in `tasks/lessons.md`: after substantial backend/
frontend integration or contract changes, build current source, stop stale backend and
frontend processes, start the current runtime, verify process identity/port ownership,
and only then rely on browser or integration observations. This does not require a
restart for trivial edits. Phase 5 remains open; no milestone completion is claimed.