# Active planning backlog

Updated: 2026-11-15. Interactive Codex supervises; GitHub issues/PRs own execution evidence.
Earlier planning and experiments remain available in Git history.

## Current outcome: public release complete

### Continuation authorized — two-line values approved

The owner explicitly approved #192's corrected two-line value display and said to keep
working. Its visual hold is satisfied, current CI/review passed, and the merged source was
published through the visible Oracle release process. Useful maintenance continues below;
UI redesign and the retired capacity programme remain out of scope.

The owner ended today's UI pairing, accepted the current Home/guide and legal copy, authorized
publishing the calculation fix, and explicitly authorized general public access. This supersedes
the earlier local-only, preview-only and no-merge holds for that scope. No new UI design session
is requested.

The live Oracle source is `265cad816492856cb73fa33094dbba2ece6465a1`, containing merged
[#222](https://github.com/Nebettadjsr/GW2/pull/222),
[#226](https://github.com/Nebettadjsr/GW2/pull/226) and
[#224](https://github.com/Nebettadjsr/GW2/pull/224), followed by approved
[#192](https://github.com/Nebettadjsr/GW2/pull/192). The source tree matches the final candidate
whose required CI and exact-head review passed. Independent QA passed 342 relevant backend tests,
including 29 safety regressions; full frontend/type/build gates passed. Every material review
finding was corrected and independently covered.

The application is public at https://gw2-crafting.de/welcome. Visible SSH deployment and public
verification passed; all three containers are healthy. The real-key HTTPS proof/owner-read/logout
check passed, as did the visible eight-destination keyless Chromium smoke. Details and practical
limits live in CURRENT_STATE_SPEC.md, not an Oracle how-to document.

Only Welcome permits indexing; tools, legal/privacy/storage, APIs and errors retain noindex and
the selected robots exclusions. Backend/admin/account authorization, exact HTTPS Origin guard,
restricted database credentials and internal ports remain protected. Public access replaces
the source-IP preview allowlist. No capacity promise, multi-account browser pass or complete
keyed browser journey is inferred.

## Issue and pull-request audit

| Item | Disposition |
| --- | --- |
| #198 status/help/history parent | Closed completed: approved key help, activity/DB size and file-selector downloads are merged and hosted |
| #213 launch parent | Closed completed after actual public release |
| #220 quiet connection-panel stack smoke | Fixed in #226; actual public HTTPS browser run passed; closed completed |
| #221/#222 legal information | Accepted and merged; all six public operator facts verified in served bundle |
| #223/#224 calculation safety | Accepted and merged after checked arithmetic, cumulative material bounds and review corrections |
| #225/#226 Home/guide | Owner-approved UI accepted and merged, including skip/history regression correction |
| #116, #154, #189, #191 | Already closed not planned; obsolete pre-release measurement work stays retired |
| #193 | Historical completed resource preparation, not an active launch dependency |
| #188/#192 | Owner-approved, merged after current CI/review, and deployed; hosted checks pass |
| #194/#231 | Completed and merged after exact-head CI/review; QA-owned repair passed focused tests/build and both controlled browser scripts |
| #21, #60, #111 | Keep coverage/correctness and optional real-DB fixture work; no automatic dispatch |
| #227/#229 | Completed and merged: targeted development dependency advisories fixed; exact-head CI/review and independent Node 22 install/audit passed |
| #228 | Prepared portable production configuration follow-up; live host override already supplies restricted role and HTTPS origin |
| #230 | Prepared follow-up for the pre-existing glob advisory; full audit still reports its three moderate package entries |

Future concrete performance improvements remain available. Do not restore the withdrawn capacity,
additional-key search, request-rate, owner-recording or pre-release analysis programme. No local
Docker/VM or load test was started in this release.

## Deployment continuity

Oracle Frankfurt AD 2 uses Ubuntu 24.04 ARM64, 2 OCPUs and 12 GB RAM. The owner selected
`gw2-crafting.de`; apex/www resolve to the host and TLS is active. Operator country and server
region are separate facts. Contact delivery is owner-confirmed. Actual postal/configuration
values stay in ignored local and protected host files rather than tracked examples or issues.

The release uses root-owned `/opt/gw2/config/production.env` and the mandatory
`/opt/gw2/config/https.compose.yaml` override with the release's production Compose file.
Do not use the original production file alone against this host. #228 owns portability work.
PostgreSQL remains disposable cache; no backup/preservation programme is required.

Operational evidence, safe runtime metadata and visible terminal details are in ignored
`target/supervision/oracle-runtime.json` and deployment logs. The public-access step's first
post-reload read saw an old worker, restored private access, then passed completely after an
explicit new-worker readiness wait. Do not report that initial check as a product defect.
The owner explicitly rejects an Oracle setup manual/runbook delegated to Claude.

## Authorization for further work

After publication, the owner authorizes reviewing all open issues/PRs, dropping obsolete
pre-release analysis and continuing useful existing tasks. That audit and bounded #220 repair
are complete. #227's bounded manifest/lock update is accepted and merged in #229, with Node 22
clean-install/resolution evidence and current full CI/coverage/build/native review. All three
targeted advisories are fixed; the pre-existing glob finding remains explicitly tracked in #230.
These changes affect development tooling, so the deployed application remains the approved #192
release. The next bounded maintenance candidate is #194's optional controlled-browser scripts;
independent QA has identified the missing normal session bootstrap and stale fixture expectations.
The supervisor accepts the separate planner handoff and selects #194 now that #227 is merged.
QA authored the two controlled browser scripts and shared session boundary/negative checks: normal
synthetic proof, current selected-output contracts, explicitly awaited ingredient lookup, and
preserved meaningful assertions/strict routes. 57 focused tests and production build passed;
visible Profit 36 stages and Discovery 15 stages passed on the final source. #231 passed current
CI/native review and is accepted/merged. No product source, UI redesign, real credentials/API, host changes,
local Docker/VM or capacity measurements are in this scope. #230 remains prepared, not activated.
No implementation attempt is active. #228 and #230 are bounded future maintenance candidates;
the retained coverage/correctness and potential performance work remains available.
Preserve potential performance
work and unrelated maintainer edits. Today's UI work is finished; do not redesign approved pages
or reopen #192's now-satisfied visual gate.

No new paid service, subscription/API billing, local VM/Docker start or renewed capacity test is
authorized by this continuation. Existing remote release operation is explicitly authorized.
Never delete live owner data merely for verification.

## Accepted product baseline to preserve

Owning behavior and UI rules remain in DOMAIN_SPEC, the crafting glossary and
FRONTEND_UX_GUIDELINES; do not duplicate their detailed contracts here.

- Source-specific Bank/Materials protection, category-local presets, reversible pending selections
  and backend-authoritative recursive calculations are accepted; #169/#170 records paired reconciliation.
- Character roster sorting, summary rows, Equipment/Inventory/Builds layouts, cropped skill pictures,
  centered equipment-width traits, in-game-style symbols and scoped stale refresh are accepted.
  Preserve template reference deduplication, retained equipment, finite recovery and account isolation.
- System Status regular/owner layout, compact key card and native no-shift help dialogs are accepted.
  #199/#200, #201/#203, #209/#211, #205/#210, #202/#207 and #206/#212 contain the bounded contracts.
  The combined owner activity file selector and real Delete cached data UI have explicit visual
  acceptance. Do not restore the planned-delete qualifier or reopen unchanged approved layouts.
- Keep the parser's narrow omitted-land-elite compatibility (#174/#175), passive-attribute parent
  coverage and existing scoped metadata boundaries. Current source/docs, not historical NOT_IMPLEMENTED
  statements, determine what is delivered.
- Mystic Forge remains a disabled approved placeholder; its future behavior is in
  agent/product-owner-requests/mystic-forge-crafting.md. Story/map-completion/recipe views outside
  the accepted character scope remain deferred. No feature expansion is released by launch preparation.

## Workflow and review controls

Planner proposes bounded contracts; supervisor reviews/releases; Claude codes. Verify actual merged
dependencies before adding auto. Existing-PR rework uses needs-rework; pairing/needs-decision/blocked
holds are real selector controls. One coding attempt at a time; no new paid API integration.

Require truthful exact-head CI, native review and necessary focused evidence. Fix material findings;
do not weaken regular assertions. All new UI returns to the owner in the local application for
explicit visual approval under agent/SUPERVISOR_INSTRUCTIONS.md. Automated browser checks and
screenshots do not substitute for that approval.

The ordinary dev app on 5173/8080 retains the owner's local working state, including
the owner activity download and cached-data deletion. The deployed source is the exact revision above;
the two-line value display is now approved and public. The previous stale runtime caused the owner
to miss the approved download despite its merged PR; update the daily app when accepting features.
Runtime launch settings/PIDs are in ignored target/supervision/daily-runtime*.json; the API key is
not stored there. Preserve unrelated local edits and existing account data during restarts.
The accepted footer runs in that same daily app. Owner-supplied operator/hosting/contact facts are
set in ignored frontend/.env; the approved information pages are publicly hosted on Oracle. The former
synthetic 5174/18081 footer preview processes are stopped. Keep verification output ignored, stop owned review
processes and remove completed worktrees after review; preserve concise evidence.
