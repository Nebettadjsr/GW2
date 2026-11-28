# UI Specification

Page-specific browser behavior and the layouts the Product Owner has accepted. Reusable presentation
rules (accessibility, color system, typography, feedback, responsive composition) are in
[FRONTEND_UX_GUIDELINES](FRONTEND_UX_GUIDELINES.md); calculation behavior is in
[DOMAIN_SPEC](DOMAIN_SPEC.md); responsibilities and API contracts are in
[ARCHITECTURE](ARCHITECTURE.md). Dated entries record accepted Product Owner decisions: preserve those
layouts during maintenance and take a deliberate redesign to the Product Owner first.

## 1. Material protection

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

## 2. Characters

The Product Owner approved the roster and Equipment/Inventory layouts through direct pairing on 2026-11-08. Preserve these choices when updating tests or adding Builds. Each character occupies one full-width row with six top-aligned, left-aligned columns: name and level/race/profession; crafting disciplines; playtime; deaths; creation date; next birthday. Use local profession/discipline icons and the profession's color for the level/race/profession line; inactive crafting disciplines are muted. Show playtime per day, deaths per hour, relative creation age plus its date, and time to next birthday plus its date. Unknown values stay unknown. The overview uses cached roster facts without fetching every character's detail/build/equipment to decorate the rows.

Place a Sort by dropdown above the roster at the top right. Default to playtime descending (most used first); alternatives are nearest birthday first, oldest first, deaths descending, alphabetic name, and alphabetic profession. Missing sort values follow known values; ties use character name. Sorting is local presentation and does not fetch or recalculate data. Keep horizontal overflow within the roster region on constrained desktop widths.

Equipment is the primary detail view. Make slots, item names and rarity easy to scan, so ascended/legendary equipment and remaining lower-quality or empty slots can be identified. Use labels as well as rarity colors. Provide Inventory and Builds sections. Inventory presents a bag layout with empty slots (confirmed 2026-11-07): bags in order, empty cells visible, and uninstalled bag positions marked as such; the backend contract is `GET /api/account/characters/{name}/inventory`. Character detail header: the selected character's name is the page title (the roster keeps "Characters"), with "← All characters" as the right-aligned header action; a small local profession glyph before the name, the subtitle `Lvl {level} {race} {active elite or base profession}`, and no routine "may be out of date"/"last synchronized" prose (real loading, failure, retry, grant, missing-data and incompatibility notices stay). Directly under the title, above the Equipment/Inventory tabs, one shared compact summary shows playtime, deaths, creation date and time to the backend-supplied next birthday (unknown stays "unknown", never zero) and the crafting disciplines one per line with tiny local icons, rating and inactive marker. The selected Equipment/Inventory tab has a filled `--color-raised` surface. Layout (desktop): no facts column; one continuous ten-cell-per-row square grid of all installed bags' slots (supplied bag/slot order, no per-bag headings or forced line breaks), compact vertical bag-icon rail right (all positions kept, uninstalled marked, no invented capacity/lock state, no click/filter/sort/drag). The page is wide enough for the three regions; the grid scrolls within its own region rather than the document. Show a refresh-required state (not an empty bag) when the saved layout is unavailable. Discovered Recipes is omitted; Map Completion remains deferred (Product Owner decision 2026-11-07).

Preserve roster position/selection when returning from details, and provide clear back/character navigation. Item links, readable quantities, keyboard access, loading/freshness/error states and account-switch cleanup must be considered in the concrete design.

Builds pairing direction (2026-11-08, #166): Skills precedes Traits. Land and aquatic skills are two compact rows of five square icons, with an environment marker at the left; names and descriptions belong in hover/focus tooltips. Three wide specialization strips stack vertically, using their actual artwork and large specialization emblem on the left. Each strip progresses horizontally through three tiers: an automatic minor trait at mid-height, then its three major alternatives stacked vertically. Selected major traits and automatic minors remain bright; unselected majors are dimmed. Place ordinary traits only by backend tier/order, retaining unplaced references separately. Trait tooltips show the supplied description and effect facts with icons. Center Skills and Traits together as one block, retaining their shared left edge. Reuse the right-hand saved-template selector; omit a second build/profession/active heading and routine saved-time prose. The Product Owner liked this composition and requested the shared centering; the Product Owner accepted the final centered layout on 2026-11-08.

In character details, the shared summary has five equal-height columns in this order: crafting disciplines, playtime, deaths, creation date, next birthday. Align all columns at the top and their contents left. The Inventory grid itself is horizontally centered; its compact bag rail is attached on the right without shifting that center line. Inventory and Equipment use the same 60px square tiles.

Equipment follows the approved Hero-panel composition: armor down the left, two weapon sets beneath it, local profession artwork in the middle, attributes above the accessories on the right. The accessory group has back item, both accessories and relic across its first row; amulet and both rings occupy the three right-hand columns below. Aquatic equipment is below that group. Fishing, Jade Bot and harvesting tools form one bottom row aligned with the lower edge of the last weapon slot; fishing and Jade Bot are horizontally centered together, and the artwork ends above them. Do not add armor dye swatches. Scale positions and artwork with the shared tile size rather than leaving the former oversized equipment canvas.

Render actual rarity borders, item names, stats and effect details in hover/focus tooltips, including runes, sigils, infusions, gathering glyphs and relic effects when supplied. Normal item tooltips contain player information, not API limitations, refresh instructions or implementation notes. Unknown effects must not be fabricated. The values block uses the in-game attribute icons, row order and derived-value pairing; a small gear opens game-mode and Reinforced Armor options. Do not restore an Equipment stats heading, weapon-set buttons, Update equipment button or technical explanatory prose. The underlying calculation scope and limitations belong in DOMAIN_SPEC §56.

Equipment template selectors are compact adjacent numbered buttons aligned right on the same line as the Equipment/Inventory navigation, with a distinct selected state and an unobtrusive marker for the actual in-game active tab. Do not add another full-width tab strip or prose explaining that an inspected tab is not in use. Builds retains the approved adjacent saved-build tabs. Preserve in-game order and initially select the active tab in each section. Browsing never changes the game. Build and equipment selections are independent. Provide keyboard operation and a visible focus state; derive any unavailable slots from reliable data without inventing an unlock or purchase workflow.

Build specializations use full in-game-style trait rows (Product Owner decision, issue #164): every minor and major choice of the specialization is visible in tier order and the selected traits are highlighted. The compact selected-only view is rejected. Specialization background artwork is shown when the backend supplies it and is optional otherwise. The backend supplies these rows via `GET /api/account/characters/{name}/build-tabs` (`minorTraits`/`majorTraits` per specialization; unresolved choices stay explicit and are not invented). Each trait carries its documented `specializationId`, `tier`, `order` (0 is valid for both) and `slot` plus a `placement` of `ORDINARY`, `NONSTANDARD`, `CONFLICT` or `UNRESOLVED`; place a row by tier/order only when `ORDINARY`, never by array position.

The screenshots do not authorize public sharing, exports, a 3D character renderer or additional statistics. The Builds page layout described in section 2 was explicitly accepted by the Product Owner on 2026-11-08; the full trait rows and saved-build tabs need no repeated decision. Domain scope is in `DOMAIN_SPEC.md`, section 56.

Builds sizing refinement (2026-11-08): use the Equipment composition's content width for the centered Skills/Traits block, scaling the trait artwork, emblems, tiles and spacing proportionally. Skills retain their tile size, with slightly enlarged, cropped pictures and a narrow inner black edge inside the light border.

## 3. System Status: account controls and owner administration

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

## 4. Feature pages: automatic stale-data refresh

Bank, Materials and Characters have no manual refresh or saved-reload buttons and no routine last-synchronized/may-be-out-of-date paragraphs. Show saved data immediately and automatically refresh stale relevant sources through the shared task mechanism; retain equipment, the inspected template and inventory throughout. Use a compact stale/updating indicator. On completion replace the view without resetting the selection or saved protection choices. On failure keep the saved view, mark the failure and link to System Status. A failed cached read must preserve the last successful view.

Refresh only sources needed by the page: Bank only Bank, Materials only material storage, Characters only character data and required optional detail sources, Crafting only its required calculation sources, Ecto only its required source. Opening Characters must not refresh recipes or materials. Sorting the roster must not fetch details. System Status remains the place for manual full-account refresh. Navigation must not duplicate ongoing refreshes or start global catalog work. Product behavior is owned by `DOMAIN_SPEC.md` section 57.

## 5. Public Home and beginner guide

Home is the first main-navigation destination and the default landing at `/home`. Its three
tabs are **About this tool**, **What you can do** and **GW2 beginner tips**. About this tool
tells the owner's origin story in short paragraphs inside a restrained dark letter panel,
using a readable, locally hosted Handlee face and a personal signature. Use the shared
charcoal surfaces, a thin frame and gold accents; keep the letter narrower than the tab
region and avoid bright parchment or ornamental inset borders. It replaces the
former promotional welcome cards and prominent connect button. There is no separate
Getting started page; System Status remains the destination for connecting a GW2 API key.
Keep the introductory story and tool links easy to scan.
Above all three tabs, a page-width gold-crafting artwork header carries **Nebet’s GW2
Crafting Tool** in a locally hosted Cinzel display face. Crop the decorative artwork to
a shallow banner and darken it behind the readable title and introduction; keep the
tab content at its existing reading width and adapt the header to narrow screens.
Tab navigation supports Arrow keys, Home/End and visible focus, with
proper tab/panel associations. Home and the footer information pages never require or
restore an account connection merely to read them.

The beginner guide is titled **Things I wish I knew sooner**. Explain from a player's
questions about starting GW2, making gold and using loot; keep a personal voice, clear
priorities and secondary caveats in side notes. Do not turn it into an exhaustive checklist,
patronizing instructions or a guaranteed farming-profit guide. Preserve direct Product
Owner text edits when maintaining spelling, spacing and layout.

In Gear & salvaging, explanatory text sits left and the relevant screenshots form a smaller
right-hand column at desktop widths; stack them on narrow screens. Gear's two screenshots
have no repeated captions underneath. Salvage-kit recommendations include recognizable small
kit images.

The magic/materials chapter pairs its three-step process with the wider vendor screenshot
and its sale notes with a smaller sale screenshot. Its gathering section contains the loot
example and Dragonfall image and ends the chapter. Hide repeated picture captions in this
chapter. The gathering-tools Wiki link uses the shared gold highlight. Present the Dragonfall
waypoint as a linked name plus a separate clickable chat code, with brief copy feedback and
the same hover/focus treatment as Mystic Forge.

Screenshots use compressed local
WebP assets, useful alternative text, intrinsic dimensions, lazy loading and responsive
sources where available; a link opens the full image. Unfilled screenshot positions render
nothing until an image is supplied. Essential instructions remain readable without images.

## 6. Delete cached data

A connected, GUID-verified account shows **Delete cached data** beside Disconnect. It opens a small native modal over the blurred backdrop ("Delete cached account data?", Cancel / Delete cached data); no inline warning panel. Opening or cancelling sends no request; Escape and backdrop click cancel only while idle; focus starts on the heading, is trapped, and returns to the trigger on cancel or Escape. Only `DELETE /api/account/data` answering `204` clears this GUID's Material Protection record and shadow and its saved Mystic Forge settings (`gw2.mysticForge.settings.v1.<account ID>`, other accounts untouched; Disconnect keeps both), runs a local-only reset (no `/session/logout`), signals other same-origin tabs (GUID only) and shows the success notice, closes the modal and moves focus to the API key field. `409 ACCOUNT_WORK_IN_PROGRESS`, network and `5xx` failures keep all local data, announce the failure in the modal and need a fresh confirmation. `ACCOUNT_DATA_DELETED` from any account request resets locally without re-proof or retry; an explicit Connect remains allowed. A `401 SESSION_REQUIRED` or `403 ACCOUNT_ACCESS_DENIED`/`ACCESS_CONTEXT_MISMATCH` answer to the deletion drops the lease and asks for a fresh confirmation ("Your connection needs to be refreshed. Confirm again…").

## 7. Footer and public information pages
 A modest footer in normal document flow links Legal
notice, Privacy and Cookies & browser storage as their own small navigation. Pages use short
headings and bullets within the prose width and never read the account. Render configured
operator/contact/hosting facts; only incomplete local builds show "To be completed" and one
explicit preview notice. Legal notice identifies the independent fan project, ArenaNet/NCSOFT
rights and absence of endorsement, the public GitHub repository, MIT code licence and
contribution/contact links. Privacy and storage copy follow DOMAIN_SPEC §§58–59 and actual
retention/monitoring behavior (the storage page names the Material Protection and Mystic Forge keys and says Delete cached data clears both), including the difference between account-linked data and
anonymity. Do not put private keys in contribution examples. Visiting public pages must not
unmount kept-alive account screens. Home alone is indexable; tool and footer pages retain
noindex metadata. Crawler directives are not access control.


## 8. Mystic Forge destination

Weapon and armor details use independently arranged boxes in a named CSS grid: calculation settings across the top; costs/comparison, owned items used and shopping/acquisition in three columns; recipe tree and dependency-ordered crafting steps below. Keep icons, quantities, Wiki links, unavailable steps and missing currencies readable. Shopping includes non-tradeable requirements; the steps can link to verified acquisition guidance (for example Clover options and the Obsidian merchant) without inventing a guaranteed random yield or adding those hints to calculation totals. Owned rows show the backend's consumed quantities and their recipe context; planned crafts are separate. Every Forge detail uses this layout family. Recipes with a variable or reference-only yield (outcome `EXPECTED` or `REFERENCE_RANDOM`) omit the material list and its progress bars, disable Reduce, and show yield and expected-proceeds lines in the costs box instead of the Trading Post comparison. Weapons, armor and all Other items show the item summary (rarity-colored name, only applicable cached metadata, Wiki link and Trading Post coin prices; rings and back pieces never show invented damage), the calculation settings, an equal-height costs/owned/shopping row, then the wider material list and slimmer crafting steps. Materials use a top row of item summary, the one-batch base recipe and compact settings (material price, use own, value own, desired output quantity with the backend's estimated batches); they have no daily, promotions or reduce controls and no crafting steps, and the browser never sends those hidden choices as anything but CRAFT, No, NO. Owned and TP shopping rows put the location (left) and value or total (right) on the same line; equipment shopping hides vendor purchases already shown as steps, Materials shopping keeps them. The material list is a static compact three-column table; every progress bar (overall, items, currencies) fills right-to-left and exists only with Use own = Yes, otherwise requirements show without bars or percentages.

The six equipment calculation selectors (material price, use own, value own, daily cooldowns, promotions, reduce amount by owned items) plus Amount are functional: a change recalculates only the selected project after the target debounce, the request/answer identity includes the settings, a late answer for other settings or target never paints, and the six choices are shared preferences (stored per verified account in browser localStorage, validated when read, never containing an access key, token or account context) that apply to every recipe and reload and are isolated per account and cleared for that account by Delete cached data (§6; a storage failure while clearing never blocks the deletion); a storage failure is stated honestly rather than hidden. Defaults: instant buy, use own No, value own No, daily Craft, promotions No, reduce No. The recipe-path switch (two buttons, a dropdown for three or more) sits at the bottom right of the amount row and keeps target and settings. No browser arithmetic: own-material value, savings and comparison totals are backend facts; unknown stays a dash and a cash-only margin (value own = No) is labelled as excluding own-material value. Reduction help explains No/Yes/Tradable only/Untradable only. Costs use stacked debit/credit rows and separators with the shared red/green treatments. Crafting steps render the backend's aggregated instruction list (`craftingSteps`, from `ForgeCraftingInstructions`): one step per recipe, output, assigned character and status, with summed crafts and direct inputs in dependency order and the root last; the browser derives no quantities from the tree. Steps are only real craft/Forge operations, merchant visits that say where and for which currency, and the estimated Clover attempt; generic Acquire/Trading Post steps are never shown (Shopping lists them).

Top navigation **Mystic Forge** sits between Crafting Discovery and Ecto Salvage. The overview lists only Forge recipes from the public cached catalogue (`GET /api/mystic-forge/recipes`) with a stable Category (All/Weapons/Armor/Materials/Other) / Type / Rarity / Search menu row (Type is always present and disabled when unavailable: weapon kinds, armor weights, Materials T2-T6, Other kinds such as ring, back, gift, miniature) and a search over name, item ID, recipe ID and real item type/subtype ("bow" matches LongBow and ShortBow); recipes are grouped per output item (Other also groups same-name same-type variants) after filtering, so an exact ID or rarity opens the matching variant and alternative recipes appear in the detail's path switch. Rows are equal-sized tiles in five columns with a rarity-colored title (full-name tooltip), icon and type/tier/weight; it renders a capped first page of 60 with "Show more", and filters, search and paging never call the planner. Available Trading Post prices use the shared coin amounts labelled Instant buy and Instant sell (the ask and the bid price); an unavailable quote is omitted from the tile rather than repeated as boilerplate, never shown as zero. Reference-only Mystic Clover rows show no guaranteed output. Opening a row shows a full-width project (`POST /api/mystic-forge/project`, account scoped, one recipe and target, no Max buy) with an amount field (default 1; Materials default to a desired output of 250 with the backend's estimated batches shown beside it), debounced and never posting an empty draft, the full hierarchical how-to including locked ordinary steps, known gold cost with unpriced items, currencies (wallet unknown is stated, not 0), shopping lines and backend economics (conditional margin, signed colors). Late answers for an earlier target, selection or deactivation are discarded; Back keeps filters, search, scroll and focuses the opened row. Outstanding source work is reread a bounded number of times. On first use a keyless project answer reporting `CREDENTIAL_REQUIRED` starts the existing keyed account-data refresh once, follows its task a bounded number of times and rereads only the selected project; a failed, timed-out or exhausted refresh shows a truthful status with Retry and no claim of further automatic checking. While the catalogue reports `completing`, it is reread a bounded number of times; after a failed or exhausted completion the open detail shows its own Retry (no Back needed). When late metadata changes the effective settings of the open recipe (an uncached row becoming a Material), the project is replanned once, keeping selection, target and preferences.
