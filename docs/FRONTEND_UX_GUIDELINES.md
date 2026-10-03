# Frontend UX/UI Guidelines

## 1. Authority and scope

This document owns reusable web presentation and interaction requirements. It implements the Product Owner intent in `agent/product-owner-requests/Request-003-UX-guidelines.md`. Frontend implementation stories must reference the relevant sections. Future Product Owner requirements may extend or supersede these rules.

The Product Owner's visual-design direction of 2026-09-25 extends that foundation: build an attractive, responsive, Guild Wars 2-inspired website with a coherent visual identity, polished feedback and natural browser behavior. The requirements below describe intended presentation, not a claim that the existing frontend already implements it.

`TARGET_ARCHITECTURE.md` owns frontend/backend responsibilities and API contracts; `DOMAIN_SPEC.md` owns calculation behavior; `TEST_STRATEGY.md` owns testing methodology. These guidelines do not introduce domain rules or change those boundaries. JavaFX is a functional and interaction reference, not a visual specification. Preserve useful workflows and information separation while adapting them to the browser.

When reviewing visible crafting or Trading Post wording, use [`crafting/GLOSSARY.md`](crafting/GLOSSARY.md) as the canonical mapping between interface labels and game/economic terms. Keep detailed definitions there; this document governs presentation and `DOMAIN_SPEC.md` governs normative calculation semantics.

## 2. Application structure and navigation

Provide recognizable application navigation, an identifiable current destination, and a page title. Implemented workflows must be reachable without scanning unrelated controls. Do not offer dead navigation entries for unfinished features.

Separate global navigation, page actions, calculation filters/settings, primary results, and selected-result information. Administrative synchronization controls and currently tracked task information belong in the System Status area; normal feature workflows must not require visiting it. A compact activity indication may link to that area.

Use consistent placement and labels for navigation, refresh, settings, status and details across pages. Navigation must not accidentally submit a calculation or synchronization operation. Within-session navigation should preserve useful controls and unfinished synchronization tracking; a return must not silently re-submit an operation or imply that leaving cancelled backend work. Do not imply persistence across a browser reload when none exists.

## 3. Layout and responsive behavior

Use the restrained dark, Guild Wars 2-inspired visual identity in section 9, with readable typography, consistent spacing and clear group boundaries. Favor clarity over decoration, hierarchy over raw density, and user-relevant information over diagnostic data. Visual polish is part of the intended result; it must support rather than obscure the data.

Use sensible maximum content widths and bounded reading widths; do not stretch every control or table across every available pixel. Data-heavy regions may use more space than explanatory text. Define shared spacing, typography, surface and state treatments rather than unrelated per-page styles.

Layouts must reflow with available width. On wide screens, primary results and selected-result details may sit side by side; on narrow screens, move details below results or into an accessible dedicated detail view. Grouped settings may collapse behind a clearly labelled control. Keep important actions and current state easy to locate in every arrangement.

A table may scroll horizontally when comparison genuinely requires two dimensions. Confine that scrolling to its region; the surrounding page must reflow. Avoid clipped controls, inaccessible detail regions, overlapping content and a fixed-width desktop canvas. Choose breakpoints from content needs, not a single assumed device size.

## 4. Crafting Profit information hierarchy

The default results table is for comparing opportunities, not displaying every backend field. Prioritize item/recipe identification, authoritative profitability, craftability and actionable economic information. Clearly distinguish per-craft values from totals wherever shown. Do not show generic row/domain state labels, badges or explanations in the normal comparison table or Selected Result panel. Keep raw diagnostic data in the backend/API for debugging and tests; crafting detail panels and tree nodes have no Technical details disclosure. Preserve UI/request/error/empty/loading messages, hidden-row notices and distinctions between missing data and zero. In the Crafting Resolution tree, show supplied stock/crafted/bought quantities, link every named item (including recursive ingredients) to its GW2 Wiki article, and use "Not craftable" only when no usable crafting path is present; an incomplete resolution does not prove no path exists. Use "Not available on TP" for an unavailable usable TP price. Move supplementary quotes, cost components and material requirements to selected-result details.

Selecting a result must be visibly and accessibly indicated and expose its available details separately from the comparison table. Separate the selected summary, recipe/resolution tree and shopping/material requirements into labelled regions. Calculation controls belong in their own visual group; search and sorting must remain easy to find. In Crafting Profit, keep the maximum displayed recipe count and Show all beside the results table title, as required by `DOMAIN_SPEC.md` §2.1.1; keep calculation inputs and the result filters in their controls panel. Preserve valid scope, settings, search and sort across explicit refresh where applicable.

Present only backend-provided detail. Expose available row details without fabricating a tree or claiming a complete explanation. Crafting Profit's tree explains the full output quantity represented by the selected detail row, as defined in `TARGET_ARCHITECTURE.md` section 10.2; the browser must render backend quantities and costs without scaling them. Keep fresh-calculation and response-association wording accurate where shown. Do not make a shopping list by adding inclusive tree costs or deriving procurement quantities in the browser. Selected-result shopping rows display the backend-selected acquisition price per item and backend purchase total; they do not choose between raw Trading Post quote sides. Label the basis of any backend-supplied material list.

The first correction must change navigation, grouping and interaction structure as well as styling. A recolored version of the same oversized table does not satisfy this requirement.

## 5. Economic values, domain states and language

Make economic results easy to scan using aligned numeric columns, consistent money formatting and clear labels for cost, proceeds and profit. Use restrained semantic color to help distinguish positive, negative, cost and unavailable values, with text, signs, symbols or structure conveying the same meaning. Color alone must never carry essential information.

Preserve backend-provided values and distinctions between known zero, missing value, blocked calculation and unvalued non-tradable material. Do not recompute business results to simplify a display. Preserve row-state meanings in `DOMAIN_SPEC.md` section 42 and keep diagnostic codes in backend/API data; the normal UI does not expose generic resolver states. Unknown codes remain distinct in API data rather than silently becoming success. The resolution tree may translate a confirmed lack of a usable recipe path to "Not craftable" or an unavailable quote to "Not available on TP"; a failed calculation with a selected recipe path is unresolved, not proof that crafting is impossible. Retain item-specific source quantities and do not infer or change the resolver's acquisition decision.

Keep backend IDs, timestamps, task UUIDs and diagnostic counters out of the primary workflow unless necessary for identification. Retain diagnostic evidence in backend/API and automated-test data rather than adding expandable technical sections to these crafting details. An item ID is an acceptable fallback when the backend provides no name; do not invent metadata or fetch it directly from GW2. Prefer understandable action and status labels over implementation terminology.

## 6. Loading, empty, error and synchronization states

Provide explicit loading, successful-empty, error, blocked and unavailable states in the region they affect. Do not show stale results as the result of a changed scope or selection. Keep important status near the relevant action and provide recovery where the existing contract supports it. Preserve useful user input through recoverable failures.

System Status should group account data, global game data, and the shared Trading Post cache into concise health cards. Show OK, Problem, Running, or Not run yet in text and color; use muted grey for states without a recorded run, and never rely on color alone. Keep only useful admin actions: account refresh and global-data check. Price refresh remains on demand through feature workflows rather than separate Profit and Discovery buttons. Distinguish accepted/pending, running, completed, failed, rejected and unknown-outcome task states according to the backend contract, and keep failure separate from failed status lookup. Do not invent progress percentages, cancellation or rollback. A navigation change must not duplicate a trigger. Feature refresh actions should describe their work and provide loading/error feedback.

## 7. Accessibility

Use WCAG 2.2 Level AA as the general baseline where reasonably applicable. Use semantic landmarks, headings, real buttons/links, labelled inputs, meaningful table headers and accessible names. Every workflow, including selection, settings disclosure, navigation and details, must be operable by keyboard with visible focus and a logical focus order.

Provide sufficient text/control contrast in the dark theme, readable text at browser zoom, and meaningful reflow at narrow widths. Do not rely on hover or color alone. Announce relevant status changes without repeatedly interrupting the user. If a dialog is used, manage entry/return focus, keyboard dismissal and focus containment appropriately. Prefer ordinary semantic controls over custom widgets when they meet the interaction need.

Record concrete accessibility limitations; do not claim full conformance from an automated checker alone.

## 8. Applying and verifying the rules

Before changing a screen, compare the implemented structure with the relevant rules and record concrete gaps in the implementation story's Result. Assess the real screen during implementation; a planning assessment based on documentation is not a visual inspection.

Verify representative wide and narrow layouts, keyboard navigation, visible focus, meaningful status/error states, and selected-detail interaction. Use frontend tests for rendering, interaction and state with controlled backend values, including values chosen to reveal accidental recalculation. Record browser/viewport evidence and limitations. Follow `TEST_STRATEGY.md` for test-layer choices; do not duplicate domain tests in the frontend.

Existing functionality and backend contracts must survive presentation changes. Navigation/layout changes do not relax `TARGET_ARCHITECTURE.md` section 17's complete-page performance requirement. Full Phase 5 performance evidence and the bounded project-health review remain distinct milestone work; visual improvement alone does not close the milestone.

## 9. Visual identity and shared color system

Aim for an elegant Tyrian trading/crafting website: deep charcoal surfaces, warm parchment-colored text, restrained crimson actions, antique-gold accents and cool teal supporting details. Draw inspiration from painted fantasy artwork, subtle brush textures, crafting-discipline emblems and item illustrations. Keep artwork around page introductions and framing; tables, forms and reading surfaces stay quiet and legible. Avoid ornate frames around every control, heavy textures behind text, excessive glow and repeated oversized hero banners on working pages.

Use one shared set of semantic design tokens across the site. The following is the baseline palette; tune contrast through shared tokens rather than introducing unrelated page-specific colors. Color values alone do not establish accessibility: verify the actual text, surface and interaction-state combinations under section 7.

| Role / token | Baseline | Use |
| --- | --- | --- |
| Background | `#11161B` | Main page canvas |
| Surface | `#1B232B` | Cards, controls and result regions |
| Raised surface | `#26323D` | Menus, dialogs and hover surfaces |
| Border | `#52616E` | Necessary input boundaries and separators |
| Text | `#F3EEE4` | Main text, warm and highly legible |
| Muted text | `#B7C0C8` | Supporting labels; never invisible essential information |
| Primary | `#B83A32` | Main action fill, paired with light text |
| Secondary | `#79C9C0` | Links, secondary outlines and supporting accents |
| Highlight | `#E1BA70` | Selected accents, discipline motifs and small focal details |
| Success / profit | `#88D4A1` | Positive outcomes, accompanied by text/signs |
| Danger / loss | `#FF9990` | Errors, destructive actions and negative outcomes |
| Warning | `#F0C36A` | Actionable caution or partial completion |
| Information / focus | `#9ACBFF` | Informational accents and visible focus outlines |

Keep brand and semantic roles separate even where hues are related: a crimson primary button does not mean an error, and a gold decoration does not imply a warning. Use brighter accent colors mainly for foregrounds/outlines; filled light-accent controls need contrasting dark text. Item-rarity colors retain their own meaning and must not replace profit/loss or selection semantics. Selected rows need a surface/border or marker as well as color, and keyboard focus must remain distinguishable from selection.

## 10. Typography, spacing and component finish

Use a readable sans-serif body face with robust system fallbacks. A restrained fantasy-inspired display face may distinguish page titles or the site identity; keep it out of numeric tables and forms. Limit font families and weights. Body and input text should normally be around 16px, with a clear heading scale and comfortable line height; do not shrink the whole interface to fit mobile. Use tabular numerals and aligned monetary values. Long item names must wrap or have an accessible way to reveal the full name.

Build shared spacing around a small scale such as 4, 8, 12, 16, 24, 32 and 48px. Give sections room to breathe while keeping related labels, controls and values together. Use consistent corner radii (roughly 8–12px for panels), restrained shadows and subtle borders. Reserve stronger elevation for actual overlays. Avoid putting every short label in its own card, excessive pill badges and visually identical boxes with no hierarchy.

Define default, hover, focus, active, selected, disabled and busy treatments for reusable controls. Each action group should have a clear primary action; supporting actions use quieter outlines or text styles. Disabled controls must remain understandable, with a nearby explanation when the reason matters. Buttons should retain their dimensions during loading. Use specific labels such as “Refresh prices” rather than vague “Submit” or implementation names.

## 11. Website character and responsive composition

Use a recognizable site header with logo/wordmark, concise destination links and a clear active page. Give each page a meaningful title and, where helpful, one short introductory sentence. Keep ordinary vertical document scrolling and a modest footer for useful guide/about links when those destinations exist. Preserve space for results rather than surrounding them with permanent toolbars, window-like chrome or multiple nested scrolling panes.

Navigation destinations must use real links, support direct URLs and normal browser Back/Forward, and have meaningful document titles. Actions use buttons. A compact mobile navigation menu must remain keyboard-accessible, expose its open state and close predictably. Avoid requiring a modal for every detail; inline or dedicated detail regions generally preserve browsing context better. Sticky headers or actions are useful only when they do not obscure content or consume excessive mobile height.

Use fluid widths and content-driven breakpoints. Around 1200–1440px is a starting maximum width for data-rich pages; explanatory copy needs a narrower reading measure. On phones, use a single-column flow, wrap action groups, stack settings and prioritize the essential result columns or a clearly labelled compact representation. Preserve access to all information and comparison tasks. Do not silently drop important values. Wide tables may retain region-level scrolling as section 3 permits, with a visible cue that more columns are available.

Check representative phone, tablet and desktop widths (for example 360, 768 and 1440px), intermediate widths, portrait/landscape and browser zoom. These are verification examples, not mandatory breakpoint values. Avoid page-wide horizontal overflow and fixed-height panels that trap content. Make touch targets comfortably sized and separated, aiming for approximately 44px for primary mobile controls. Essential actions must not depend on hover; tooltips supplement visible labels rather than replacing them.

## 12. Graphics, iconography and motion

Use item images, crafting-discipline symbols, coin denominations and contextual illustrations where they improve recognition or atmosphere. A compact painted header accent or an illustrated empty state can provide character without distracting from results. Use a consistent icon family, stroke weight and size for navigation/actions; reserve game-specific images for recognizable game concepts. Avoid mixing arbitrary emoji with several incompatible icon styles as the site's visual language.

Pair unfamiliar icons with text. Icon-only controls need accessible names and a discoverable explanation. Decorative images/icons must not produce redundant screen-reader announcements; meaningful images need appropriate alternative text. Coin symbols supplement explicitly understandable currency values. Show a stable fallback when an image is missing, preserving the item's name, quantity and occupied state.

Prefer existing project assets or appropriately licensed/authorized artwork and record asset attribution where required. Do not imply official ArenaNet affiliation. Obtain game metadata/images through the established backend or approved local/static assets, preserving TARGET_ARCHITECTURE's external-API boundary. This guideline does not require adding a new icon service, UI framework or animation dependency.

Size and compress images for their display role, use responsive sources where appropriate, reserve image dimensions to prevent layout shifts, and defer offscreen decorative images. Essential text and results must render even when optional artwork fails. Avoid autoplay video, parallax and heavy animated backgrounds on analysis pages.

Use short, subtle transitions for disclosure, hover and selection—roughly 120–200ms as a starting point. Motion should clarify a state change, not delay interaction. Respect reduced-motion preferences: remove decorative movement, replace shimmer/rotation with a static busy indication where needed, and keep the accompanying status understandable.

## 13. Polished feedback without developer noise

Use feedback appropriate to the work and localized to the affected region:

| Situation | Presentation |
| --- | --- |
| Initial results load | Stable layout with restrained skeleton rows or an inline spinner and “Loading crafting opportunities…” |
| Explicit price refresh | Busy state on “Refresh prices”, optionally a rotating refresh arrow; prevent duplicate submission |
| Long synchronization | Clearly labelled pending/running state; a determinate progress bar only when the backend supplies meaningful progress |
| Work with no measurable progress | Indeterminate indicator and plain-language activity label; no invented percentages or countdowns |
| No matching results | Explain the empty state and offer a relevant action such as clearing filters |
| No synchronized data | Explain the prerequisite and link to the existing synchronization workflow |
| Recoverable failure | Inline explanation near the affected content plus retry where the API contract permits it |
| Completed action | Brief, quiet confirmation when useful; avoid accumulating success banners |

Do not add artificial delays to show an animation. Skeletons must resemble the eventual content and stop on error/empty/success. Preserve control positions and useful content during refresh, but clearly label any retained older snapshot; never present it as the newly requested scope's result. Expose busy state accessibly without announcing every animation frame or polling tick. Whole-page blocking overlays are inappropriate for a local request.

Normal pages should communicate what the user can do and what happened, without requiring knowledge of the backend. Keep request IDs, endpoint paths, HTTP jargon, raw state enums, debug counters and repetitive polling messages out of the normal workflow. For example, prefer “Could not load prices. Try again.” over a raw endpoint/error dump. Relevant failures and uncertain outcomes must still be visible; simplifying wording must not turn them into success. Secondary diagnostic disclosure is for genuinely useful troubleshooting, not a default panel on every page.

Visual acceptance under section 8 must include the actual loading, empty, failure and image-fallback states as well as populated pages. Check consistent palette/type/spacing across screens, mobile navigation, touch and keyboard interaction, contrast, reduced motion and layout stability. Capture representative browser evidence. Decorative polish must preserve real-data completeness and the existing page-load budget; a loading animation is not evidence that a page has completed loading.
