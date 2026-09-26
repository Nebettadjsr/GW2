## Story ID

STORY-WEB-009

## Title

Add replaceable browser favicon support

## Status

TODO

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

Not started.

## Blockers

None.

