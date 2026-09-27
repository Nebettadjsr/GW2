## Story ID

STORY-WEB-009

## Title

Add replaceable browser favicon support

## Status

DONE

## Milestone

milestone-05

## Goal

Provide a standard browser favicon asset location and precise replacement instructions for the Product Owner.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md Phase 5 frontend migration objective.
- docs/TARGET_ARCHITECTURE.md section 12.1: standard replaceable favicon asset.
- agent/PROJECT_STATE.md supplied continuity note: remaining Request-004 favicon scope.

## Context

Request-004 asks for favicon support and the exact path for the final PO-provided image, not a final branding design.

## Acceptance Criteria

1. Establish frontend/public/favicon.ico as the documented replaceable favicon file and reference its served URL from the frontend document using a standard browser icon link. Respect the frontend's existing base-path/build behavior.
2. Provide a simple neutral valid placeholder asset at that path; the PO can replace it without editing application source. Do not embed the image as base64 or require an image service.
3. Document the exact repository path, expected .ico format, replacement/build steps and browser-cache refresh considerations in the frontend's existing user-facing setup documentation. Do not imply the placeholder is final PO artwork.
4. Verify built-page asset resolution and a real browser request loading the favicon; navigation and API behavior remain unchanged. Record checks in Result.

## Required Tests

- Build the frontend and check the emitted icon reference resolves to the asset, including the currently supported base path.
- One browser check that the icon request succeeds and replacement uses the documented asset location. No new automated test suite is required for this static-asset change.

## Constraints

- Favicon only; no new branding decision, image-generation dependency, icon-delivery architecture or UI redesign.
- Do not require the final PO icon to complete the integration.

## Dependencies

STORY-WEB-004 (DONE).

## Definition of Done

A replaceable favicon is served by the built frontend and the PO has precise instructions for supplying the final file.

## Result

DONE, frontend only. Nothing under `src/`, no backend change, no new dependency and no branding
decision: one static file, one document link, one documentation section and one browser check.

**Delivered.**

- **AC 1** — `frontend/public/favicon.ico` is the replaceable file, and `frontend/index.html` carries
  one standard `<link rel="icon" type="image/x-icon" href="%BASE_URL%favicon.ico" />`. `%BASE_URL%` is
  Vite's own base-path substitution, so the emitted href follows the build's configured base exactly
  like the emitted script and stylesheet URLs, and Vite copies `public/` into `dist/` unprocessed,
  unhashed and unrenamed. No configuration was added or changed.
- **AC 2** — the committed file is a plain 32×32 neutral grey rounded square with a light border,
  written as a real binary ICO (single 32-bit image plus mask, 4286 bytes, sha256 `bed9e566fb26…`).
  It is a file, not base64 in source and not a generated asset: no data URI, no image service, no
  generator script and no new dependency is part of the repository, and nothing in the application
  source refers to its contents, so the PO replaces the icon by overwriting that one file. The bytes
  were produced once by a throwaway Node script outside the repository, kept out of it deliberately:
  the placeholder is disposable and the project must not acquire an icon-generation step to own it.
- **AC 3** — `README.md` "Running locally" gained a "Browser favicon" section: the exact path, the
  expected `.ico` format (a single 32×32 works, 16/32/48 multi-size is the usual choice), the
  overwrite-then-reload / `npm run build` steps, and the favicon-cache problem with three ways out
  (hard reload, opening `/favicon.ico` directly, private window or cleared image cache) plus the
  explicit warning that an unchanged tab icon is not by itself evidence of failure. The section calls
  the current file a neutral placeholder, "not artwork … not intended as the project's final icon".
- **AC 4** — verified below; navigation and API behaviour unchanged.

**Verification (all commands run in `frontend/`).**

- `npm run build` — type-check plus production build, clean. `dist/index.html` emits
  `<link rel="icon" type="image/x-icon" href="/favicon.ico" />` with no unresolved `%BASE_URL%`, and
  `dist/favicon.ico` is byte-identical to `public/favicon.ico` (`md5sum` equal, 4286 bytes).
- Base-path behaviour: `npx vite build --base=/gw2/ --outDir dist-base-check` emitted
  `href="/gw2/favicon.ico"` alongside `src="/gw2/assets/index-*.js"` and copied the asset; the
  temporary output directory was removed again.
- New `npm run smoke:favicon` (`frontend/scripts/favicon-browser-smoke.mjs`) — **PASSED, 7 steps** in
  real Chrome 152 against the script's own `stubOrigin` on `127.0.0.1:5186` (probed free first, the
  page asserted served by that process): the built document exposes exactly one icon link resolving to
  `http://127.0.0.1:5186/favicon.ico`; a real in-page request for it returned **HTTP 200,
  `image/x-icon`, 4286 bytes with the sha256 of `public/favicon.ico`**; the browser decoded those
  bytes to a **32×32** image, so the placeholder is valid and not merely present; all six destinations
  still opened, no `/api/` route was involved in the icon, no request left the origin and there was no
  page error.
- `npm run smoke:icons` — **PASSED, 10 steps**, re-run specifically to check this change breaks no
  existing assertion about image requests (`tasks/lessons.md`, absence assertions): unchanged at 5
  image requests all on the icon route and 17 same-origin browser requests. Chromium loads a tab icon
  through its own loader, which Playwright does not report as a page request, so the favicon appears in
  no script's request list.
- Not run: `npm test` and the other smoke scripts. Nothing in this change is reachable from the Vitest
  suite (it never loads `index.html`), and the full-regression gate is GitHub Actions
  (`TEST_STRATEGY.md` §20/§36). No new automated suite was added, per this story's Required Tests.

**Limits and honesty.** The placeholder is explicitly **not** PO artwork and no branding decision was
made or implied. Nothing about performance, page-load timing or any Phase 5 exit criterion is claimed.
The base-path evidence is a temporary `--base=/gw2/` build, not a deployment: the application has no
non-root base configured and none was introduced. The favicon was observed loading from the built
output over HTTP against a controlled origin; it was not verified against the running backend or a
containerized deployment, neither of which serves `frontend/dist/` today.

**Documentation.** `README.md` (the user-facing section required by AC 3) and
`docs/CURRENT_ARCHITECTURE.md` §5.11 only — the frontend file-layout tree, the new "Browser tab icon"
paragraph, the corrected "Not built yet" paragraph (which claimed there is no favicon) and one command
table row. `TEST_STRATEGY.md` §12.2's verification levels gain nothing from a static-asset check, so
it was left alone.

**Observation for the planner, not acted on.** This story cites `TARGET_ARCHITECTURE.md` §12.1 as the
authoritative source for a "standard replaceable favicon asset", but §12.1 as written covers only
item/recipe icon delivery and states no favicon rule (`Request-004`'s resolution note assumed it did).
The implementation followed the story's own acceptance criteria, which are unambiguous, and no rule was
invented or reinterpreted to fill the gap. Whether §12.1 should gain that sentence is an architecture
decision outside this story's scope.

## Blockers

None.

