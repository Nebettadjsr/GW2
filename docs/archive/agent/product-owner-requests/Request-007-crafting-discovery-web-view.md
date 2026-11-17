# Product Owner Request

## Status

RESOLVED

## Title

Add Crafting Discovery Helper as a dedicated web view

## Requested Change

The current web frontend is missing the Crafting Discovery Helper.

Add Crafting Discovery as a dedicated user-facing page and make it reachable through the application's main navigation.

The existing JavaFX Crafting Discovery Helper is the functional reference for what this feature is intended to do. It is not a visual specification. The web version should follow the established frontend UX/UI guidelines and use the same modern application structure as the other web views.

---

### Purpose

Crafting Discovery helps the user find recipes that can still be discovered through Guild Wars 2's crafting discovery system.

The view should help with two primary goals:

- efficiently level a crafting discipline by finding relevant undiscovered recipes;
- identify discoveries that may also be economically worthwhile.

---

### Character and crafting discipline

The user selects:

- a character;
- a crafting discipline available to that character.

The result set must respect the selected character's current crafting level/rating.

Do not show recipes requiring a crafting rating above the selected character's current rating.

Recipe-learning/unlock eligibility must continue to follow the authoritative domain rules already established for the application rather than introducing a separate frontend interpretation.

---

### Discovery eligibility

The list should contain recipes that:

- are discoverable through normal ingredient-combination crafting discovery;
- have not already been learned/unlocked according to the application's established recipe-ownership semantics;
- are valid for the selected character and crafting discipline;
- do not exceed the character's current crafting level;
- are not vendor/scroll-learned recipes or other recipes that cannot actually be obtained through the normal Discovery system.

Recipes that cannot be discovered by combining materials must not appear merely because they exist in the recipe database.

---

### Result information

For each discovery candidate, provide the information needed to evaluate it, including at least:

- item/recipe name;
- recipe/crafting level;
- cost of missing materials;
- Trading Post sell value of the crafted output;
- profit per craft.

Profit per craft should represent the established economic comparison between the output value and the relevant material cost.

Backend/domain calculations remain authoritative. Do not recreate economic calculations independently in Vue.

---

### Leveling relevance

Recipes near the selected character's current crafting level are particularly useful for crafting progression because they provide better leveling relevance than significantly lower-level discoveries.

The UI should therefore make recipe level easy to see and provide a way to order results so the user can efficiently look for discoveries near the current crafting level.

Do not invent a combined XP/profit score.

Leveling relevance and economic profitability are separate concepts.

---

### Sorting

Provide useful sorting for at least:

- recipe/crafting level, with high/current-level-relevant recipes easy to surface;
- item Trading Post sell value;
- profit per craft.

The user should therefore be able to use the same result set either to:

- find useful discoveries for leveling;
- or search for profitable discoveries.

---

### Search and filtering

Provide search/filtering appropriate for the result list so the user can quickly locate an item or narrow a large discovery set.

Filters and sorting should preserve the selected character and discipline.

Refreshes must not unnecessarily reset valid user selections.

---

### Calculation controls

Reuse applicable existing calculation concepts rather than creating Discovery-specific versions without need.

Where relevant, the view may expose controls such as:

- use owned materials;
- allow buying missing materials;
- maximum purchase budget;
- Trading Post buy mode;
- Trading Post sell mode.

These controls must use the existing domain semantics and backend calculations.

Do not silently change Crafting Profit behavior when implementing Discovery.

---

### Selected recipe details

Selecting a discovery result should populate a dedicated detail area rather than forcing all information into the comparison table.

Where supported by the backend/domain data, selected details should include:

- recipe information;
- material requirements;
- owned versus missing materials;
- buy requirements/costs;
- crafting/resolution tree;
- useful blocked/unavailable explanations;
- shopping/material list.

The result row and detail view should use recipe identity rather than table position.

The web implementation should follow the same general interaction principle used for Crafting Profit:

```text
results / comparison list
        +
selected-result details
```

rather than expanding the main table with every available field.

Navigation

Add Crafting Discovery to the normal application navigation.

It should be a first-class application view alongside the other main user workflows rather than a hidden developer route or secondary debug screen.

Visual and UX requirements

Follow docs/FRONTEND_UX_GUIDELINES.md.

In particular:

use a clear page hierarchy;
keep controls grouped separately from results;
make the primary result list easy to scan;
use the right/detail area for recipe-specific information;
avoid exposing raw backend/debug information as primary content;
use readable money formatting and established positive/negative visual treatments;
provide clear loading, empty, blocked and error states;
use item icons through the application-wide icon strategy when available;
preserve keyboard-operable selection and other established accessibility behavior.

The JavaFX screen demonstrates useful functional separation between controls, result list and selected recipe details. Preserve or improve that separation without copying the old layout pixel-for-pixel.

Why / Product Intent

Crafting Discovery is one of the application's core user-facing workflows and existed in the JavaFX application.

The web migration is incomplete without it.

The feature should answer:

Which recipes can this character still discover at their current crafting level, what would the missing materials cost, and what economic result would crafting the discovered item produce?

The user should be able to use the view either to efficiently progress a crafting discipline or to identify economically interesting discoveries.

The web version should provide that workflow in a modern, structured interface rather than simply reproducing the old JavaFX controls.

Constraints
Follow the existing authoritative Discovery domain rules.
Do not invent separate frontend calculation logic.
Do not treat vendor/scroll recipes as normal discovery candidates.
Do not show recipes above the selected character's applicable crafting level.
Keep leveling relevance separate from profitability.
Preserve backend-authoritative monetary values.
Reuse shared frontend patterns and components where appropriate.
Use the application-wide item icon strategy when it becomes available.
The JavaFX view is a functional reference, not a visual specification.
Do not alter Crafting Profit behavior merely to implement Discovery.
Additional Context

The previous JavaFX Discovery view included the core workflow of:

character/discipline selection;
material and buying controls;
Trading Post buy/sell modes;
sorting/search;
recipe-level comparison;
buy cost;
output sell value;
profit per craft;
selected-recipe tree;
shopping/material details.

The planner should compare existing backend/application capabilities and already-planned Phase 5 work before creating duplicate implementation stories.

## Planner Resolution

2026-09-26: RESOLVED as planning coverage. Reused docs/DOMAIN_SPEC.md section
2.2.2 and docs/TARGET_ARCHITECTURE.md sections 12-14. Created
agent/stories/STORY-WEB-012-crafting-discovery-page.md and queued it in
agent/stories/BACKLOG.md for navigation, individual scope/rating inputs,
backend-owned eligibility and economics, sortable/searchable comparison,
calculation controls, fresh selected details and browser verification.
Reused agent/stories/STORY-API-006-crafting-selector-options.md,
agent/stories/STORY-APP-012-request-local-resolution-detail.md and
agent/stories/STORY-API-008-crafting-resolution-endpoints.md and the completed
Discovery table API indexed in agent/stories/BACKLOG.md. The new consumer reuses
agent/stories/STORY-WEB-010-shared-item-icon-presentation.md when available;
agent/stories/STORY-API-009-web-item-icon-metadata.md remains the separate shared
backend prerequisite. No duplicate API, icon infrastructure or eligibility
algorithm was commissioned. Delivery and Phase 5 completion remain pending.