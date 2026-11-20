# Frontend UX/UI Guidelines

## 1. Authority and scope

This document owns reusable web presentation and interaction requirements. It implements the Product Owner intent in `docs/archive/agent/product-owner-requests/Request-003-UX-guidelines.md`. Frontend implementation issues must reference the relevant sections. Future Product Owner requirements may extend or supersede these rules.

The Product Owner's visual-design direction of 2026-09-25 extends that foundation: build an attractive, responsive, Guild Wars 2-inspired website with a coherent visual identity, polished feedback and natural browser behavior. The requirements below describe intended presentation, not a claim that the existing frontend already implements it.

The later [supported browser viewport decision](DOMAIN_SPEC.md#supported-browser-viewport)
governs the informational notice/desktop canvas and supersedes earlier phone/tablet layout
expectations and the initial blocking proposal.
Public reading pages retain their responsive presentation. The remaining requirements apply
within that product support policy.

`ARCHITECTURE.md` owns frontend/backend responsibilities and API contracts; `DOMAIN_SPEC.md` owns calculation behavior; `TEST_STRATEGY.md` owns testing methodology. These guidelines do not introduce domain rules or change those boundaries. Preserve useful workflows and information separation in the browser; the retired desktop interface is not a visual specification.

When reviewing visible crafting or Trading Post wording, use [`crafting/GLOSSARY.md`](crafting/GLOSSARY.md) as the canonical mapping between interface labels and game/economic terms. Keep detailed definitions there; this document governs presentation and `DOMAIN_SPEC.md` governs normative calculation semantics.

## 2. Application structure and navigation

Provide recognizable application navigation, an identifiable current destination, and a page title. Implemented workflows must be reachable without scanning unrelated controls. Do not offer dead navigation entries for unfinished features.

Separate global navigation, page actions, calculation filters/settings, primary results, and selected-result information. Administrative synchronization controls and currently tracked task information belong in the System Status area; normal feature workflows must not require visiting it. A compact activity indication may link to that area.

Use consistent placement and labels for navigation, refresh, settings, status and details across pages. Navigation must not accidentally submit a calculation or synchronization operation. Within-session navigation should preserve useful controls and unfinished synchronization tracking; a return must not silently re-submit an operation or imply that leaving cancelled backend work. Do not imply persistence across a browser reload when none exists.

GW2 API key entry, replacement and disconnect controls belong on System Status only. Keep that page
reachable while disconnected and on direct navigation/reload. Other account-dependent pages retain
their page heading and show a concise link to System Status when a connection is needed; do not
repeat the key form above every page. Opening System Status must preserve the crafting screens'
cached controls/results and must not submit another calculation.

### 2.1 Material protection

Bank keeps the original slot order and empty cells, grouped into 30-slot tabs with ten square
tiles per row and an occupied/available count. Materials keeps backend category/position order.
Do not merge duplicate Bank stacks or collapse empty cells to make the grid denser.

Below the saved-selection count, explain the current actions concisely: "Click an item to
protect it from Crafting Profit calculations; right-click to keep a quantity or open
Crafting Profit." Selection changes save automatically; activation remains a separate
Crafting Profit choice.

Bank and Materials tiles are fully clickable. A click on the checkbox, background, icon or
quantity toggles protection exactly once, as does keyboard activation. Every accepted change
is saved immediately; there is no global Save/Discard control or pending-change text. The
Product Owner accepted the final stack and note presentation after publication on 2026-11-17.

On Materials, clicking NONE or a finite reservation selects ALL; clicking ALL clears it.
On Bank, clicking an actual stack changes only that stack: fully kept becomes 0; partial or
0 becomes the stack's full current count. Preserve the other stacks and save the new total
as the item's fixed target. A manual total outside the supported range leaves the existing
reservation and native checkbox state unchanged. Numeric entry and stale layouts allocate
in ascending current Bank slot order. The kept target remains fixed after rearranging,
splitting, absence or low stock. Materials item-wide actions and legacy/bulk ALL retain
their meaning; they do not become permanent reservations of particular old slot numbers.

Right-click, ContextMenu or Shift+F10 opens **Reserve quantity…** and **Open Crafting Profit**.
The quantity editor is a small centered modal with one numeric input and Apply/Cancel;
accept whole numbers from 0 to 2,147,483,647, zero clears, and invalid input stays visible
with an explanation. The Profit shortcut fills the existing search with the cached item
name (item ID if unavailable), allowing the existing ingredient search to find uses for it.
Opening the menu or cancelling the editor changes no reservation. Keep the menu within
the viewport, preserve keyboard operation and restore focus to its trigger on dismissal.

Finite reservations use a **solid yellow border**, distinct from ordinary tiles and the
ALL selection outline. Both fully covered and partially covered stacks use that border;
partial stacks also have the native mixed checkbox state, while untouched duplicates
stay ordinary. Provide accessible "keeping N" labels but draw no clipped "Keep N" text
inside the tile. A compact readable note list sits to the right of each Bank tab grid and
each Materials category grid, reflowing below it when needed within supported tool widths. Deduplicate
notes per grid and show cached item name (honest Item #ID fallback), saved fixed target and
source without clamping the target to stock. Keep horizontal scrolling within the grid;
the page must not overflow. Tile titles and ARIA labels use cached item names with an ID
fallback. Distinguish protection from its Crafting Profit activation state without
interfering with item links, quantity display or keyboard navigation.

Selections are independent per page, including when the same item type occurs in both stores. One top-level button toggles Select all / Deselect all; Bulk, category and preset actions act atomically on their eligible items (a mixed group becomes ALL; only a fully ALL group clears) with a single preference write. Bulk actions affect displayed selectable item types and preserve stored selections for absent items. Materials also has a Select all / Deselect all toggle per category: a partial category becomes fully selected first; a full category is deselected. Tier and special presets appear only on Materials and use the same toggle behavior. Place T1–T6 below the Intermediate Crafting Materials heading, left of its category toggle on the same line above the tiles; place the four-item preset equivalently in Advanced Crafting Materials. Other categories and the other page remain unchanged. Every Materials tile has a checkbox; Bank uses the ingredient eligibility rule in DOMAIN_SPEC §9.1. These controls and the independent store selection were confirmed by the Product Owner on 2026-11-08.

Provide preset buttons for every intermediate-material tier (T1 through T6), including families such as dust and blood. Basic crafting materials and gemstones are excluded from these presets; users can still select them individually. Provide an additional preset selecting Mystic Clovers, Mystic Coins, Globs of Ectoplasm and Obsidian Shards; its visible button label is "Clovers, coins, ecto & obsidian", with a tooltip naming the four full item names. Presets apply immediately and are saved automatically like any other selection change. The design must allow further presets. Verify exact item-ID membership from game data; do not infer crafting tier from rarity or select unrelated items automatically. These membership choices were confirmed by the Product Owner on 2026-11-07.

Crafting Profit has a protection toggle and selected-item count. The checkbox is labelled exactly "Respect excluded items/ mats" and sits in the calculation panel's right-hand checkbox column, in this order: Use own materials; Respect excluded items/ mats; Allow buying; Allow daily craft. The Calculation heading occupies the full width alone; the first checkbox aligns with the Discipline selection below it. Crafting Profit has no Mystic Forge checkbox, roots or nested routes (Mystic Forge is its own destination between Discovery and Ecto Salvage). Protection is unchecked by default for a new account/browser; saved per-account activation is kept, not reset per visit. A tiny (i) control at the end of the protection row hides the committed unique item count and the Bank and/or Materials selection links in a hover/focus panel: it stays open while the pointer moves from the icon into the panel and while focus is inside, closes on Escape, and has an accessible name. Links reflect which stores have committed selections. The empty-selection warning floats separately without shifting the layout. Attempting to activate it without a committed selection must show a short popup/floating warning explaining that nothing is selected; do not imply that stock is being protected. Repeated Materials tiles and item-wide ALL selections must remain coherent; Bank finite allocations may intentionally differ across stacks of the same item.


Remember the saved selection and activation setting per verified account on the same browser.
Account changes must not display or apply another account's preferences. If browser storage
cannot save preferences, retain coherent in-memory state and disclose that persistence is
unavailable. Returning to Crafting Profit applies changed reservations once to the active
calculation; edits on Bank/Materials do not start crafting calculations in hidden screens.
An unchanged return keeps the matching completed selected detail; an unfinished or mismatched
request must not install a stale explanation. Calculation behavior belongs in
`DOMAIN_SPEC.md`, section 9.1.

### 2.2 Characters

The Product Owner approved the roster and Equipment/Inventory layouts through direct pairing on 2026-11-08. Preserve these choices when updating tests or adding Builds. Each character occupies one full-width row with six top-aligned, left-aligned columns: name and level/race/profession; crafting disciplines; playtime; deaths; creation date; next birthday. Use local profession/discipline icons and the profession's color for the level/race/profession line; inactive crafting disciplines are muted. Show playtime per day, deaths per hour, relative creation age plus its date, and time to next birthday plus its date. Unknown values stay unknown. The overview uses cached roster facts without fetching every character's detail/build/equipment to decorate the rows.

Place a Sort by dropdown above the roster at the top right. Default to playtime descending (most used first); alternatives are nearest birthday first, oldest first, deaths descending, alphabetic name, and alphabetic profession. Missing sort values follow known values; ties use character name. Sorting is local presentation and does not fetch or recalculate data. Keep horizontal overflow within the roster region on constrained desktop widths.

Equipment is the primary detail view. Make slots, item names and rarity easy to scan, so ascended/legendary equipment and remaining lower-quality or empty slots can be identified. Use labels as well as rarity colors. Provide Inventory and Builds sections. Inventory presents a bag layout with empty slots (confirmed 2026-11-07): bags in order, empty cells visible, and uninstalled bag positions marked as such; the backend contract is `GET /api/account/characters/{name}/inventory`. Character detail header (STORY-CHAR-011): the selected character's name is the page title (the roster keeps "Characters"), with "← All characters" as the right-aligned header action; a small local profession glyph before the name, the subtitle `Lvl {level} {race} {active elite or base profession}`, and no routine "may be out of date"/"last synchronized" prose (real loading, failure, retry, grant, missing-data and incompatibility notices stay). Directly under the title, above the Equipment/Inventory tabs, one shared compact summary shows playtime, deaths, creation date and time to the backend-supplied next birthday (unknown stays "unknown", never zero) and the crafting disciplines one per line with tiny local icons, rating and inactive marker. The selected Equipment/Inventory tab has a filled `--color-raised` surface. Layout (STORY-CHAR-011, desktop): no facts column; one continuous ten-cell-per-row square grid of all installed bags' slots (supplied bag/slot order, no per-bag headings or forced line breaks), compact vertical bag-icon rail right (all positions kept, uninstalled marked, no invented capacity/lock state, no click/filter/sort/drag). The page is wide enough for the three regions; the grid scrolls within its own region rather than the document. Show a refresh-required state (not an empty bag) when the saved layout is unavailable. Story is deferred by Product Owner decision (2026-11-07). Discovered Recipes is omitted; Map Completion remains deferred.

Preserve roster position/selection when returning from details, and provide clear back/character navigation. Item links, readable quantities, keyboard access, loading/freshness/error states and account-switch cleanup must be considered in the concrete design.

Builds pairing direction (2026-11-08, #166): Skills precedes Traits. Land and aquatic skills are two compact rows of five square icons, with an environment marker at the left; names and descriptions belong in hover/focus tooltips. Three wide specialization strips stack vertically, using their actual artwork and large specialization emblem on the left. Each strip progresses horizontally through three tiers: an automatic minor trait at mid-height, then its three major alternatives stacked vertically. Selected major traits and automatic minors remain bright; unselected majors are dimmed. Place ordinary traits only by backend tier/order, retaining unplaced references separately. Trait tooltips show the supplied description and effect facts with icons. Center Skills and Traits together as one block, retaining their shared left edge. Reuse the right-hand saved-template selector; omit a second build/profession/active heading and routine saved-time prose. The Product Owner liked this composition and requested the shared centering; the Product Owner accepted the final centered layout on 2026-11-08.

In character details, the shared summary has five equal-height columns in this order: crafting disciplines, playtime, deaths, creation date, next birthday. Align all columns at the top and their contents left. The Inventory grid itself is horizontally centered; its compact bag rail is attached on the right without shifting that center line. Inventory and Equipment use the same 60px square tiles.

Equipment follows the approved Hero-panel composition: armor down the left, two weapon sets beneath it, local profession artwork in the middle, attributes above the accessories on the right. The accessory group has back item, both accessories and relic across its first row; amulet and both rings occupy the three right-hand columns below. Aquatic equipment is below that group. Fishing, Jade Bot and harvesting tools form one bottom row aligned with the lower edge of the last weapon slot; fishing and Jade Bot are horizontally centered together, and the artwork ends above them. Do not add armor dye swatches. Scale positions and artwork with the shared tile size rather than leaving the former oversized equipment canvas.

Render actual rarity borders, item names, stats and effect details in hover/focus tooltips, including runes, sigils, infusions, gathering glyphs and relic effects when supplied. Normal item tooltips contain player information, not API limitations, refresh instructions or implementation notes. Unknown effects must not be fabricated. The values block uses the in-game attribute icons, row order and derived-value pairing; a small gear opens game-mode and Reinforced Armor options. Do not restore an Equipment stats heading, weapon-set buttons, Update equipment button or technical explanatory prose. The underlying calculation scope and limitations belong in DOMAIN_SPEC §56.

Equipment template selectors are compact adjacent numbered buttons aligned right on the same line as the Equipment/Inventory navigation, with a distinct selected state and an unobtrusive marker for the actual in-game active tab. Do not add another full-width tab strip or prose explaining that an inspected tab is not in use. Builds retains the approved adjacent saved-build tabs. Preserve in-game order and initially select the active tab in each section. Browsing never changes the game. Build and equipment selections are independent. Provide keyboard operation and a visible focus state; derive any unavailable slots from reliable data without inventing an unlock or purchase workflow.

Build specializations use full in-game-style trait rows (Product Owner decision, issue #164): every minor and major choice of the specialization is visible in tier order and the selected traits are highlighted. The compact selected-only view is rejected. Specialization background artwork is shown when the backend supplies it and is optional otherwise. The backend supplies these rows via `GET /api/account/characters/{name}/build-tabs` (`minorTraits`/`majorTraits` per specialization; unresolved choices stay explicit and are not invented). Each trait carries its documented `specializationId`, `tier`, `order` (0 is valid for both) and `slot` plus a `placement` of `ORDINARY`, `NONSTANDARD`, `CONFLICT` or `UNRESOLVED`; place a row by tier/order only when `ORDINARY`, never by array position.

The screenshots do not authorize public sharing, exports, a 3D character renderer or additional statistics. The Builds page layout described in section 2.2 was explicitly accepted by the Product Owner on 2026-11-08; the full trait rows and saved-build tabs need no repeated decision. Domain scope is in `DOMAIN_SPEC.md`, section 56.

Builds sizing refinement (2026-11-08): use the Equipment composition's content width for the centered Skills/Traits block, scaling the trait artwork, emblems, tiles and spacing proportionally. Skills retain their tile size, with slightly enlarged, cropped pictures and a narrow inner black edge inside the light border.

### 2.3 System Status: account controls and owner administration

Present the saved own-account counts as a compact summary grid inside the Account data card,
with clear labels and prominent numbers. Reflow to fewer columns when needed within supported
tool widths; keep refresh date/source metadata separate from the count summary. The Product
Owner accepted the owner-only Accounts/catalog/actions layout on 2026-11-11 and the revised
regular-user layout on 2026-11-12. Preserve both accepted layouts during maintenance.

Ordinary layout (accepted 2026-11-12): the Account data card uses the full content width and shows four own-account counts (Characters, Unlocked recipes, Material types, Total materials stored). Shared service health is one compact, `role="status"` line (items and recipes, Trading Post prices; OK/Running/Problem/Not run yet) instead of two large cards. A count is never shown as 0 unless that source was published empty; "Not loaded yet", "Unknown" and "may be out of date" are distinct, and sources the key does not permit are omitted. The owner-only accounts/catalog/actions composition accepted on 2026-11-11 is preserved, with the common account-count summary added. Visual approval is complete; a future deliberate redesign requires its own Product Owner review.

Ordinary users see their key/connection controls, their account-data age and manual account refresh. Shared-system health uses concise generic labels. Owner-only controls provide detailed diagnostics and global item, recipe and full-TP refresh actions, clearly distinguished from refreshing one account. Do not render privileged details for ordinary users and do not rely on hidden controls for authorization. Admin identity is resolved by the backend as described in `ARCHITECTURE.md`.

For owners, keep each shared dataset's stored count, oldest/newest per-record refresh times
and full-refresh action in the same card. Show synced item counts separately when stored
items include placeholders. Place stored-account and last-60-minute activity counts beside
the account-data card. Price freshness belongs in the Trading Post card; keep process-lifetime
reference-check diagnostics secondary and expandable. Do not show an idle full-refresh action
as though the stored dataset itself has never been refreshed. These layouts are approved.

**Application activity** is owner-only and loads when opened or explicitly refreshed; do not
add a polling timer. Show its five-minute foreground-account estimate separately from the
60-minute stored-account figure, and keep partial/startup/unavailable states explicit. History
is collapsed initially and paginated. Offer a readable file dropdown grouped by Day and Week,
newest first, with the newest available day selected by default; show the server-supplied
range, retained size and coverage state. Download one chosen CSV rather than asking for raw
timestamps. A removed file prompts a manifest refresh instead of choosing a different period.
Withdrawal of owner capability clears privileged presentation and aborts pending downloads.

### 2.4 Feature pages: automatic stale-data refresh

Bank, Materials and Characters have no manual refresh or saved-reload buttons and no routine last-synchronized/may-be-out-of-date paragraphs. Show saved data immediately and automatically refresh stale relevant sources through the shared task mechanism; retain equipment, the inspected template and inventory throughout. Use a compact stale/updating indicator. On completion replace the view without resetting the selection or saved protection choices. On failure keep the saved view, mark the failure and link to System Status. A failed cached read must preserve the last successful view.

Refresh only sources needed by the page: Bank only Bank, Materials only material storage, Characters only character data and required optional detail sources, Crafting only its required calculation sources, Ecto only its required source. Opening Characters must not refresh recipes or materials. Sorting the roster must not fetch details. System Status remains the place for manual full-account refresh. Navigation must not duplicate ongoing refreshes or start global catalog work. Product behavior is owned by `DOMAIN_SPEC.md` section 57.

### 2.5 Public Home and beginner guide

Home is the first main-navigation destination and the default landing at `/welcome`. Its four
tabs are **Welcome**, **What you can do**, **Getting started** and **GW2 beginner tips**.
Keep the introductory promise, intended audiences, tool links and short account-to-crafting
steps easy to scan. Tab navigation supports Arrow keys, Home/End and visible focus, with
proper tab/panel associations. Home and the footer information pages never require or
restore an account connection merely to read them.

The beginner guide is titled **Things I wish I knew sooner**. Explain from a player's
questions about starting GW2, making gold and using loot; keep a personal voice, clear
priorities and secondary caveats in side notes. Do not turn it into an exhaustive checklist,
patronizing instructions or a guaranteed farming-profit guide. Preserve direct Product
Owner text edits when maintaining spelling, spacing and layout.

In the Gear & salvaging and magic/materials sections, explanatory text sits left and the
relevant screenshots form a smaller right-hand column at desktop widths; stack them on
narrow screens. Gear's two screenshots have no repeated captions underneath. Salvage-kit
recommendations include recognizable small kit images. Screenshots use compressed local
WebP assets, useful alternative text, intrinsic dimensions, lazy loading and responsive
sources where available; a link opens the full image. Unfilled screenshot positions render
nothing until an image is supplied. Essential instructions remain readable without images.

## 3. Desktop tools and public reading layouts

Render the width notice required by [the product policy](DOMAIN_SPEC.md#supported-browser-viewport)
as concise readable text above the actual tool content, fitting the viewport. It is a warning,
not a replacement page, overlay or access gate. Keep the desktop canvas available through
horizontal scrolling, with no clipping or width-based hiding/inert controls. Do not promise
future mobile optimization. Resizing must preserve normal route/account/tool/modal state;
it neither submits another calculation nor closes an open modal. The concrete new notice
requires Product Owner review in the local application before acceptance.

Use the restrained dark, Guild Wars 2-inspired visual identity in section 9, with readable typography, consistent spacing and clear group boundaries. Favor clarity over decoration, hierarchy over raw density, and user-relevant information over diagnostic data. Visual polish is part of the intended result; it must support rather than obscure the data.

Use sensible maximum content widths and bounded reading widths; do not stretch every control or table across every available pixel. Data-heavy regions may use more space than explanatory text. Define shared spacing, typography, surface and state treatments rather than unrelated per-page styles.

Tool layouts must remain usable across supported desktop widths and short viewport heights.
Primary results and selected detail retain their accepted hierarchy, with any necessary
reflow or regional scrolling preserving every value and action. Public Home, guide and
information pages reflow at narrower widths. No phone-specific tool composition is required.

A table may scroll horizontally when comparison genuinely requires two dimensions. Confine
that scrolling to its labelled region and preserve keyboard access. Avoid clipped controls,
inaccessible details or overlapping content at supported widths. Public reading pages must
not inherit a tool's wide canvas. At narrow tool viewports, document-level horizontal scrolling
is intentional; no phone-specific rearrangement or usability polish is required.

## 4. Crafting Profit information hierarchy

The default results table is for comparing opportunities, not displaying every backend field. Prioritize item/recipe identification, authoritative profitability, craftability and actionable economic information. Clearly distinguish per-craft values from totals wherever shown. Do not show generic row/domain state labels, badges or explanations in the normal comparison table or Selected Result panel. Keep raw diagnostic data in the backend/API for debugging and tests; crafting detail panels and tree nodes have no Technical details disclosure. Preserve UI/request/error/empty/loading messages, hidden-row notices and distinctions between missing data and zero. In the Crafting Resolution tree, show supplied stock/crafted/bought quantities, link every named item (including recursive ingredients) to its GW2 Wiki article, and use "Not craftable" only when no usable crafting path is present; an incomplete resolution does not prove no path exists. Use "Not available on TP" for an unavailable usable TP price. Move supplementary quotes, cost components and material requirements to selected-result details.

Selecting a result must be visibly and accessibly indicated and expose its available details separately from the comparison table. Separate the selected summary, recipe/resolution tree and shopping/material requirements into labelled regions. Calculation controls belong in their own visual group; search and sorting must remain easy to find. In Crafting Profit, keep the maximum displayed recipe count and Show all beside the results table title, as required by `DOMAIN_SPEC.md` §2.1.1; keep calculation inputs and the result filters in their controls panel. Preserve valid scope, settings, search and sort across explicit refresh where applicable.

Product Owner presentation choices (issue #153) for the Crafting Profit results: pointer hover emphasizes only the whole table row, with no border on the recipe-name button, while keyboard focus keeps the visible `:focus-visible` outline. During an active refresh with last-good rows, only the compact green "Stale data – refreshing…" indicator sits above the table: no explanation, account-source, Trading Post coverage or outstanding-work text and no "Showing …" summary; the summary returns when the replacement arrives. A failed or timed-out refresh keeps the full failure explanation, backend/account/Trading Post context and retry, and a pending state with no rows stays informative.

Present only backend-provided detail. Expose available row details without fabricating a tree or claiming a complete explanation. Crafting Profit's tree explains the full output quantity represented by the selected detail row, as defined in `ARCHITECTURE.md`; the browser must render backend quantities and costs without scaling them. Keep fresh-calculation and response-association wording accurate where shown. Do not make a shopping list by adding inclusive tree costs or deriving procurement quantities in the browser. Selected-result shopping rows display the backend-selected acquisition price per item and backend purchase total; they do not choose between raw Trading Post quote sides. Label the basis of any backend-supplied material list.

Where an effective finite reservation applies to a displayed requirement or shopping item,
show its saved target and source as a quiet note (for example, "Keep 250 in material
storage"). The note describes a preference, not an additional requirement quantity; do
not subtract it again from backend stock use, purchase amounts or costs.

The first correction must change navigation, grouping and interaction structure as well as styling. A recolored version of the same oversized table does not satisfy this requirement.

## 5. Economic values, domain states and language

Make economic results easy to scan using aligned numeric columns, consistent money formatting and clear labels for cost, proceeds and profit. Use restrained semantic color to help distinguish positive, negative, cost and unavailable values, with text, signs, symbols or structure conveying the same meaning. Color alone must never carry essential information.

Preserve backend-provided values and distinctions between known zero, missing value, blocked calculation and unvalued non-tradable material. Do not recompute business results to simplify a display. Preserve row-state meanings in `DOMAIN_SPEC.md` section 42 and keep diagnostic codes in backend/API data; the normal UI does not expose generic resolver states. Unknown codes remain distinct in API data rather than silently becoming success. The resolution tree may translate a confirmed lack of a usable recipe path to "Not craftable" or an unavailable quote to "Not available on TP"; a failed calculation with a selected recipe path is unresolved, not proof that crafting is impossible. Retain item-specific source quantities and do not infer or change the resolver's acquisition decision.

Keep backend IDs, timestamps, task UUIDs and diagnostic counters out of the primary workflow unless necessary for identification. Retain diagnostic evidence in backend/API and automated-test data rather than adding expandable technical sections to these crafting details. An item ID is an acceptable fallback when the backend provides no name; do not invent metadata or fetch it directly from GW2. Prefer understandable action and status labels over implementation terminology.

## 6. Loading, empty, error and synchronization states

Provide explicit loading, successful-empty, error, blocked and unavailable states in the region they affect. Do not show stale results as the result of a changed scope or selection. Keep important status near the relevant action and provide recovery where the existing contract supports it. Preserve useful user input through recoverable failures.

System Status keeps account data as its own concise card and gives one short explanation that account data belongs to the account while shared game data is reused across accounts. Ordinary users see one compact generic shared-health line; owners instead see an aggregate Accounts card (total stored, active past 60 minutes; counts only) and Items, Recipes and Trading Post prices dataset groups, each placing stored-row count, per-record oldest/newest fetch time and its full-catalog action together, alongside one compact "GW2 API" card of the same size and styling (Good / Needs update with Last checked; neutral Checking, Not checked or Unavailable otherwise) with no button, links or technical details — the backend checks automatically and the card follows cached reads. Empty, partially known (rows without a fetch time), timestamp-unknown, unavailable (never shown as zero) and not-reported summaries are rendered distinctly and never imply catalog completeness or a successful full refresh. Show OK, Problem, Running, or Not run yet in text and color; use muted grey for states without a recorded run, and never rely on color alone. Everyone keeps account refresh; the owner-only section (shown only after backend owner confirmation) adds shared diagnostics and the item, recipe and Trading Post full-catalog controls, replacing the visible global-check action. Price refresh remains on demand through feature workflows rather than separate Profit and Discovery buttons. Distinguish accepted/pending, running, completed, failed, rejected and unknown-outcome task states according to the backend contract, and keep failure separate from failed status lookup. Do not invent progress percentages, cancellation or rollback. A navigation change must not duplicate a trigger. Feature refresh actions should describe their work and provide loading/error feedback.

## 7. Accessibility

Use WCAG 2.2 Level AA as the general baseline where reasonably applicable. Use semantic landmarks, headings, real buttons/links, labelled inputs, meaningful table headers and accessible names. Every workflow, including selection, settings disclosure, navigation and details, must be operable by keyboard with visible focus and a logical focus order.

Provide sufficient text/control contrast, readable browser zoom, supported-desktop scrolling
and meaningful narrow reflow for public reading pages. Do not rely on hover or color alone.
Announce relevant status changes without repeatedly interrupting the user. If a dialog is used,
manage entry/return focus, keyboard dismissal and focus containment appropriately. Prefer
ordinary semantic controls over custom widgets when they meet the interaction need.

**Scannable help and modals.** Help must be scannable at a glance: a short heading, a few short separated facts or numbered steps (semantic lists, bold only a short leading phrase), and detail behind a collapsed native `<details>`; never a paragraph wall. Keep actions and official links in a footer apart from prose. A help popup is a true modal: native `<dialog>` with `showModal()` and a dimmed, lightly blurred `::backdrop`, centered, about 30rem wide (39rem for the longer safety explanation), at least 1.25rem padding, 1.5 line-height, constrained to the viewport and scrolling inside only when needed. Background is inert, focus stays inside, and Escape or Close returns focus to the trigger. Opening or closing must cause zero layout shift: no background element, scroll offset or page width may move (use `scrollbar-gutter: stable`), and nothing is inserted inline. Replace routine status sentences with state-bearing controls; keep sentences for errors, retries and working states.

The API-key creation guide uses four evenly spaced steps: link to the ArenaNet Applications page in the first step, **Create a new key** with **all permissions**, **Copy & Paste**, then **Click Connect**. Keep the Steam/Epic sign-in hint subordinate with its own spacing. Do not render permission chips. Its footer opens the safety explanation in the same modal and retains focus return to the original trigger.

Safety help uses five short facts: read-only account protection, ArenaNet's exclusion of email/password/payment details, permission and revocation control, no separate sign-up, and the difference between Disconnect (removes the key from the browser session) and the real **Delete cached data** action (removes saved account data from our database). Avoid a blanket claim that no personal data is involved: account IDs and character names are still account-linked information. The collapsed explanation separates retention in the current browser tab and transient server use, ArenaNet-verified account identity and reuse with another valid key for that account, the cached game-data categories, and caching's purpose for API request limits and demanding calculations. Explain that reloads keep the key, fresh tabs normally start without it, and duplicated/restored tabs may keep it; do not promise that clearing cached files erases it. Disconnect removes the current tab's key. Revocation blocks reconnection, while an existing session can remain valid for up to 15 minutes. Omit a repeated retained-cache disclaimer under the stored-data list. Its two footer links name ArenaNet explicitly and lead to key management and ArenaNet's own explanation of API keys. Put each link on its own line; let Close move below them at narrow widths.

Record concrete accessibility limitations; do not claim full conformance from an automated checker alone.

## 8. Applying and verifying the rules

Before changing a screen, compare the implemented structure with the relevant rules and
record concrete gaps and verification in the GitHub issue or pull request. Assess the real
screen during implementation; a planning assessment based on documentation is not a visual
inspection.

Verify supported desktop layouts, the informational narrow-width notice with tool content still visible and horizontally scrollable, and public-reading exceptions,
keyboard navigation, visible focus, meaningful status/error states and selected details.
Use controlled values that reveal accidental frontend recalculation. Retain real-browser
journeys for native event/focus/navigation and integrated request wiring; unit tests alone
do not establish those properties. Record named journeys, viewports and evidence limitations
under TEST_STRATEGY. Do not duplicate domain tests or call browser journey counts code coverage.

Existing functionality and backend contracts must survive presentation changes. Preserve
the page-load requirement in `ARCHITECTURE.md` and verify any concrete
performance regression introduced by a change. The Product Owner retired the synthetic
pre-release capacity-measurement programme; it is not an extra visual-acceptance gate.
Useful production activity monitoring and specific future optimizations remain in scope.

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

Navigation destinations use real links, direct URLs, normal Back/Forward and meaningful
document titles. Actions use buttons. Navigation remains keyboard-accessible, including
public reading at narrow widths and the informational tool notice. Avoid a modal for every
detail; inline or dedicated regions usually preserve browsing context better. Sticky
elements must not obscure content or consume excessive height in a short desktop viewport.

Use fluid widths and content-driven breakpoints within the supported tool range. Around
1200–1440px is a starting maximum width for data-rich pages; explanatory copy needs a narrower
reading measure. Preserve all information and comparison tasks at supported widths. Wide
tables may retain region-level scrolling with a visible cue. Public reading pages use a
single-column flow and wrap their navigation/actions when their narrower layout needs it.

Check normal desktop, the exact supported boundary and the unsupported notice immediately
below it, plus short desktop height and browser zoom. Narrow public-page checks remain
applicable; phone/tablet tool-layout passes are retired. Avoid page-wide overflow and
fixed-height panels that trap content in supported layouts. Retain comfortable control
targets and keyboard alternatives; essential actions must not depend on hover. Tooltips
supplement visible labels rather than replacing them.

## 12. Graphics, iconography and motion

Use item images, crafting-discipline symbols, coin denominations and contextual illustrations where they improve recognition or atmosphere. A compact painted header accent or an illustrated empty state can provide character without distracting from results. Use a consistent icon family, stroke weight and size for navigation/actions; reserve game-specific images for recognizable game concepts. Avoid mixing arbitrary emoji with several incompatible icon styles as the site's visual language.

Pair unfamiliar icons with text. Icon-only controls need accessible names and a discoverable explanation. Decorative images/icons must not produce redundant screen-reader announcements; meaningful images need appropriate alternative text. Coin symbols supplement explicitly understandable currency values. Show a stable fallback when an image is missing, preserving the item's name, quantity and occupied state.

Prefer existing project assets or appropriately licensed/authorized artwork and record asset attribution where required. Do not imply official ArenaNet affiliation. Obtain game metadata/images through the established backend or approved local/static assets, preserving ARCHITECTURE.md's external-API boundary. This guideline does not require adding a new icon service, UI framework or animation dependency.

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

Visual acceptance under section 8 includes loading, empty, failure and image-fallback states,
populated desktop pages, the support notice and public-page reflow. Check consistent
palette/type/spacing, keyboard/focus, contrast, reduced motion and layout stability. Capture
representative browser evidence. Decorative polish preserves real-data completeness and the
existing page-load budget; a loading animation does not establish completed loading.

### Delete cached data (STORY-DELETE-002)

A connected, GUID-verified account shows **Delete cached data** beside Disconnect. It opens a small native modal over the blurred backdrop ("Delete cached account data?", Cancel / Delete cached data); no inline warning panel. Opening or cancelling sends no request; Escape and backdrop click cancel only while idle; focus starts on the heading, is trapped, and returns to the trigger. Only `DELETE /api/account/data` answering `204` clears this GUID's Material Protection record and shadow, runs a local-only reset (no `/session/logout`), signals other same-origin tabs (GUID only) and shows the success notice. `409 ACCOUNT_WORK_IN_PROGRESS`, network and `5xx` failures keep all local data, announce the failure in the modal and need a fresh confirmation. `ACCOUNT_DATA_DELETED` from any account request resets locally without re-proof or retry; an explicit Connect remains allowed.

**Footer and public information pages.** A modest footer in normal document flow links Legal
notice, Privacy and Cookies & browser storage as their own small navigation. Pages use short
headings and bullets within the prose width and never read the account. Render configured
operator/contact/hosting facts; only incomplete local builds show "To be completed" and one
explicit preview notice. Legal notice identifies the independent fan project, ArenaNet/NCSOFT
rights and absence of endorsement, the public GitHub repository, MIT code licence and
contribution/contact links. Privacy and storage copy follow DOMAIN_SPEC §§58–59 and actual
retention/monitoring behavior, including the difference between account-linked data and
anonymity. Do not put private keys in contribution examples. Visiting public pages must not
unmount kept-alive account screens. Home alone is indexable; tool and footer pages retain
noindex metadata. Crawler directives are not access control.


## Mystic Forge destination

Weapon and armor details use independently arranged boxes in a named CSS grid: calculation settings across the top; costs/comparison, owned items used and shopping/acquisition in three columns; recipe tree and dependency-ordered crafting steps below. Keep icons, quantities, Wiki links, unavailable steps and missing currencies readable. Shopping includes non-tradeable requirements; the steps can link to verified acquisition guidance (for example Clover options and the Obsidian merchant) without inventing a guaranteed random yield or adding those hints to calculation totals. Owned rows show the backend's consumed quantities and their recipe context; planned crafts are separate. Material promotions and other Forge details retain their existing layout while equipment layout is being paired.

The six equipment calculation selectors (material price, use own, value own, daily cooldowns, promotions, reduce amount by owned items) plus Amount are functional: a change recalculates only the selected project after the target debounce, the request/answer identity includes the settings, a late answer for other settings or target never paints, and the choices survive Back within the session but reset on an account change. No browser arithmetic: own-material value, savings and comparison totals are backend facts; unknown stays a dash and a cash-only margin (value own = No) is labelled as excluding own-material value. Reduction help explains No/Yes/Tradable only/Untradable only. Costs use stacked debit/credit rows and separators with the shared red/green treatments. Crafting steps are only real craft/Forge operations, merchant visits that say where and for which currency, and the estimated Clover attempt; generic Acquire/Trading Post steps are never shown (Shopping lists them).

Top navigation **Mystic Forge** sits between Crafting Discovery and Ecto Salvage. The overview lists only Forge recipes from the public cached catalogue (`GET /api/mystic-forge/recipes`) with All/Weapons/Armor/Materials/Other tabs and a search over name, item ID and real item type/subtype ("bow" matches LongBow and ShortBow); it renders a capped first page with "Show more", and tab, search and paging never call the planner. Quotes are labelled Instant sell (bid) and Listing sell (ask) per output unit; a missing quote reads "Not traded / price unavailable", never zero. Reference-only Mystic Clover rows show no guaranteed output. Opening a row shows a full-width project (`POST /api/mystic-forge/project`, account scoped, one recipe and target, no Max buy) with a target field (default 1; "Batches" for random-output recipes), debounced and never posting an empty draft, the full hierarchical how-to including locked ordinary steps, known gold cost with unpriced items, currencies (wallet unknown is stated, not 0), shopping lines and backend economics (conditional margin, signed colors). Late answers for an earlier target, selection or deactivation are discarded; Back keeps tab, search, scroll and focuses the opened row. Outstanding source work is reread a bounded number of times. On first use a keyless project answer reporting `CREDENTIAL_REQUIRED` starts the existing keyed account-data refresh once, follows its task a bounded number of times and rereads only the selected project; a failed, timed-out or exhausted refresh shows a truthful status with Retry and no claim of further automatic checking. While the catalogue reports `completing`, it is reread a bounded number of times.
