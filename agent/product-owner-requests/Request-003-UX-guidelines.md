# Product Owner Request

## Title

Establish frontend UX/UI standards and redesign the initial web frontend

## Requested Change

The current web frontend does not provide an acceptable user-facing application structure.

The first web implementation currently presents large amounts of information,
controls, synchronization state and technical details together on essentially
one flat page. Important information has weak visual hierarchy, navigation is
minimal, the main crafting table consumes nearly the full page width, and
useful interaction patterns from the JavaFX application have been lost.

Establish authoritative frontend UX/UI guidelines and bring the web frontend
into compliance with them.

Create and maintain:

`docs/FRONTEND_UX_GUIDELINES.md`

This document should define project-wide rules for future web UI work.

## Product Intent

The web frontend should feel like a normal modern application intended for
end users, not like a developer/debug interface exposing backend data.

The first web UI does not need to be the final visual design. It should be a
clean, modern, usable foundation that can evolve as the Product Owner adds
features and refines workflows.

The existing JavaFX application is a functional and interaction reference,
not a visual specification. Useful information hierarchy and workflows from
it should not be discarded merely because the implementation moves to the web.

For example, the Crafting Profit view deliberately separates:

- the main list of crafting opportunities;
- controls affecting the calculation;
- details for the currently selected result;
- the recipe/crafting tree;
- the shopping list.

The web version should preserve or improve such separation instead of forcing
all information into one oversized table.

## Required UX/UI Principles

The guidelines and implementation must address at least:

- clear application-level navigation and recognizable page structure;
- clear hierarchy between global navigation, page actions, filters/settings,
  primary results and secondary/detail information;
- separation of unrelated workflows such as synchronization and crafting analysis;
- progressive disclosure: secondary and technical information should not
  dominate the normal user workflow;
- responsive layouts that adapt to available viewport width instead of relying
  on one fixed desktop arrangement;
- sensible maximum widths, spacing, grouping and readable typography;
- use of panels, cards, side/detail regions, dialogs or other appropriate
  structures instead of placing every value into one flat table;
- tables only for information that genuinely benefits from row/column comparison;
- prioritization of the most useful columns instead of showing every available
  backend value by default;
- detailed information for a selected item shown separately from the primary
  results where appropriate;
- visually scannable economic results;
- preservation of useful semantic coloring from the JavaFX application, such as
  quickly distinguishing profitable/cost/result values;
- color must not be the only indication of meaning; text, symbols or structure
  must also communicate important states;
- consistent controls, spacing, typography, status presentation and interaction
  patterns across pages;
- explicit loading, empty, error, blocked and synchronization states;
- backend IDs, timestamps, task UUIDs, diagnostic counters and similar technical
  information must not dominate the normal UI; expose them only where genuinely
  useful or through secondary diagnostic/details presentation;
- avoid displaying implementation terminology to the user when a user-oriented
  description exists;
- important actions and current state should be easy to locate without scanning
  the entire page;
- keyboard/accessibility-friendly controls and semantic HTML;
- WCAG 2.2 Level AA as the general accessibility baseline where reasonably
  applicable.

## Crafting Profit First-Draft Expectations

The Crafting Profit screen should not be implemented as one full-width table
containing every available field.

The primary results area should emphasize information useful for quickly
evaluating crafting opportunities, including profitability and craftability.

Selecting a result should expose its detailed information separately, including
the resolution/crafting tree and related information.

On sufficiently wide screens this may use a main-content + details layout,
similar in purpose to the existing JavaFX arrangement.

On narrower screens the same information should adapt appropriately, for
example by moving details below the results or into a dedicated expandable/detail
view rather than simply shrinking everything.

Calculation controls/settings should be visually grouped and separated from the
result data.

Synchronization controls/status should be part of an appropriate application
area and must not visually dominate the Crafting Profit workflow.

## Visual Direction

Use a modern, restrained dark-theme design appropriate for a data-heavy
Guild Wars 2 utility.

The interface should favor:

clarity > decoration  
hierarchy > raw information density  
user-relevant information > backend/debug information  
fast visual scanning > displaying every possible value simultaneously

The application may use Guild Wars 2-inspired visual cues where appropriate,
but should not sacrifice readability or usability for decoration.

## Standards and Responsiveness

Use established modern web usability and accessibility practices rather than
inventing layout behavior ad hoc.

The interface must respond meaningfully to different viewport sizes.

A data table may require horizontal scrolling when its structure genuinely
requires two-dimensional presentation, but the surrounding application should
reflow rather than becoming a fixed-width desktop canvas.

## Architecture / Documentation

`docs/FRONTEND_UX_GUIDELINES.md` becomes the authoritative owner for reusable
web UX/UI rules.

`docs/TARGET_ARCHITECTURE.md` should reference these guidelines for frontend
presentation requirements rather than duplicating them.

Frontend implementation stories must reference the relevant UX/UI guidelines.

Future Product Owner UI requirements may extend or supersede individual design
rules.

## Immediate Correction

Review the currently implemented web frontend against the new guidelines.

Create the necessary work to restructure the initial frontend rather than
continuing to build additional pages on the current flat layout.

Do not merely restyle the existing table with different colors. The issue is
information architecture, navigation, hierarchy, layout and usability as well
as visual appearance.

Preserve existing backend functionality while correcting the frontend
presentation and interaction structure.

## Why / Product Intent

The web migration is intended to produce a more usable application, not simply
to reproduce backend data in HTML.

A user should be able to understand where they are, what actions are available,
what information matters, and where additional detail can be found without
having to understand the application's backend architecture.

The initial web frontend should establish reusable UX patterns so future
features can be added coherently instead of each screen inventing a new layout.

## Status

RESOLVED

## Planner Resolution

Established `docs/FRONTEND_UX_GUIDELINES.md` as the reusable presentation-rule owner, covering the requested application structure, Crafting Profit hierarchy, dark visual direction, responsive layouts, state/diagnostic presentation and accessibility baseline. Added a reference in `docs/TARGET_ARCHITECTURE.md` section 12 without duplicating the rules.

Created `agent/stories/STORY-WEB-004-application-navigation-layout.md` and `agent/stories/STORY-WEB-005-crafting-profit-information-hierarchy.md`, prioritized in `agent/stories/BACKLOG.md` To Do before the existing trace prerequisite. These stories require implementation-time review of the actual frontend, structural correction and browser/keyboard verification. The active bank/materials story is untouched; correction follows its completion and precedes additional page work.

Resolution means the request is represented in authoritative rules and executable planning, not that the redesign has been implemented. The complete resolution-tree expectation is retained in `docs/FRONTEND_UX_GUIDELINES.md` section 4 and the existing `docs/TARGET_ARCHITECTURE.md` section 13 contract; `agent/stories/STORY-DOM-020-semantic-resolution-trace.md` remains its domain prerequisite. Subsequent bounded API/tree work remains necessary under the supplied Phase 5 resolution-tree criterion. Existing Phase 5 scope already covers frontend implementation and tests, so no roadmap exit-criterion change or milestone transition is needed. Guideline application and verification are ongoing requirements for frontend implementation stories, not a new recurring review task.