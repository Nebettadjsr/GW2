# Active planning backlog

Updated: 2026-11-14. Interactive Codex supervises; native GitHub Codex plans; Claude implements
one released story at a time. GitHub issues/PRs own execution evidence; this file owns planning
order, authorization and handoffs. Earlier planning and experiments remain available in Git history.

Immediate handoff: after accepted footer/log integration, the owner says “pls continue building”.
Release #216 as the sole portable production-configuration implementation. Host/domain/TLS choices,
actual Oracle setup and verified legal content remain later coordinated launch work. Claude codes;
Codex supervises and performs actual hosting setup with the owner. No Oracle manual is requested.

## Current authorization: public production preparation

The Product Owner ends the multi-user/capacity-test and pre-launch analysis programme. Additional
real account keys are unavailable. Prepare the application for public Oracle hosting, then observe
actual use through bounded operational monitoring. Further capacity research, owner recordings,
request-rate experiments, marginal-storage measurements and Oracle performance qualification are
not launch prerequisites. Do not claim a supported-user capacity or a passed benchmark.

Remove experiment-only tooling; retain ordinary correctness/CI/browser/container smoke checks,
account-isolation fixtures, the accepted owner operations history/downloads and Oracle host metrics.
Issues #116, #154, #189 and #191 are withdrawn as not planned, not completed. Concrete functional
defects and optional optimizations remain available for later prioritization.

This authorizes preparation and normal review/merge of accepted work. It does not authorize paid
services, a domain/email purchase, public deployment, Docker/VM startup or renewed load tests.
Coordinate a concrete later stack smoke/release window with the owner; never delete live owner data
during verification. Preserve unrelated main-checkout edits.

The 2026-11-14 continuation releases independent #216 configuration now: owner-GUID forwarding,
separate required production credentials, bounded logs, public operator-fact build inputs and
meaningful static/offline checks. No Docker/VM startup or new capacity work on the owner's host.
Do not silently choose the still-unselected public TLS/edge or claim target-host execution.

## Production planning order

Native handoff and amendments: [PLAN-LAUNCH-001 / #213](https://github.com/Nebettadjsr/GW2/issues/213).
IDs follow the executor's STORY-[AREA]-[NNN] convention; the original planner's longer labels map
to these same bounded contracts.

| Contract | Outcome | Supervisor handoff |
| --- | --- | --- |
| [STORY-LAUNCH-001 / #214](https://github.com/Nebettadjsr/GW2/issues/214) | Retire capacity experiment tools; preserve correctness and operational monitoring | Accepted cleanup [#217](https://github.com/Nebettadjsr/GW2/pull/217); no new work released here |
| [STORY-LAUNCH-002 / #215](https://github.com/Nebettadjsr/GW2/issues/215) | Public footer/info pages and noindex defaults | Owner-approved shell accepted in [#218](https://github.com/Nebettadjsr/GW2/pull/218); no repeated visual gate for this layout |
| [STORY-LAUNCH-003 / #216](https://github.com/Nebettadjsr/GW2/issues/216) | Concrete production application/container configuration | Sole released coding contract; independent preparation now, actual host/HTTPS execution later; no setup guide/runbook |
| STORY-LAUNCH-004 | Verified legal/privacy/storage content and publication gate | Draft in #213; factual operator/deployment inputs required |
| STORY-LAUNCH-005 | Exact-revision release handoff | Draft in #213; deployment remains a later concrete action |

The existing two-line value correction [#188/#192](https://github.com/Nebettadjsr/GW2/pull/192)
is separate functional work with its local visual gate, not a resource prerequisite. Optional browser
smoke bootstrap gap [#194](https://github.com/Nebettadjsr/GW2/issues/194) is regular correctness work,
not obsolete load tooling; retain it. Other coverage/functional requests (#21, #60, #111) are not
automatically activated. Do not revive historical KPI/resource story rows.

## Confirmed launch facts and open inputs

- Operator: private individual living/operating in Germany, explicitly confirmed by the owner.
  No company, tax status, legal name, service address or public email has been supplied.
- Oracle remains the selected host. Tenancy region/architecture/allocation, public domain/DNS,
  TLS setup and contact-email service need guided setup with the owner. Operator country and
  server region are distinct; OCI is not a domain registrar.
- Interactive Codex performs the actual Oracle/domain/DNS/HTTPS setup with the owner. The owner
  explicitly rejects delegating an Oracle how-to document/runbook to Claude; do not recreate it.
- Minimize unnecessary exposure of the home address. Verify an appropriate real service-address or
  authorized-recipient arrangement if chosen; do not anonymize or invent mandatory facts.
  Private-individual status alone does not establish an exemption for the public app.
- App, legal/privacy/storage and fallback/error pages default to noindex through response headers
  and HTML metadata. Keep required legal information directly public. Do not block crawlers from
  reading noindex or promise protection from scraping. Only later separately approved app
  explanations, usage guidance, beginner tips and audience pages may be indexable; no sitemap yet.
- API key is per-tab sessionStorage; GW2_SESSION holds an opaque session identifier. Saved exclusions
  use per-account localStorage. Final consent treatment follows actual purpose/necessity across all
  browser storage, not the presence of a cookie. No advertising/tracking feature is requested.
- Final legal/privacy content needs verified contact, processing, retention and hosting facts.
  Preview placeholders must never be presented as publication-ready legal information.

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

The ordinary dev app on 5173/8080 now runs the locally integrated accepted master changes, including
the owner activity download and cached-data deletion. The previous stale runtime caused the owner
to miss the approved download despite its merged PR; update the daily app when accepting features.
Runtime launch settings/PIDs are in ignored target/supervision/daily-runtime*.json; the API key is
not stored there. Preserve unrelated local edits and existing account data during restarts.
The accepted footer runs in that same daily app. Its confirmed Germany operator-country value is
set in ignored frontend/.env.local; unknown operator/hosting details remain unset. The former
synthetic 5174/18081 footer preview processes are stopped. Keep verification output ignored, stop owned review
processes and remove completed worktrees after review; preserve concise evidence.
