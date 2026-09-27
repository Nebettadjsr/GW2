# Product Owner Request

## Status

RESOLVED

## Title

Make Crafting Resolution compact and user-focused

## Requested Change

The current Crafting Resolution tree contains too much technical detail and becomes unnecessarily long, especially for recipes with several nested crafting levels.

The tree should be simplified so that it primarily communicates:

- what item is required,
- how many are required,
- whether that requirement comes from stock, crafting or buying,
- which character can craft a crafted requirement,
- and the nested ingredient structure when the user chooses to inspect it.

### Remove introductory explanation

Remove the explanatory paragraph currently shown directly under `Crafting resolution`.

The user does not need the long explanation about the resolution being a separate calculation, output batches, starting inventory, budget or daily state in the normal UI.

If this distinction remains technically important, it may be documented elsewhere or exposed only as optional technical information.

### Keep the useful status labels

The current compact state labels are useful and should remain, for example:

- `From stock`
- `Crafted`
- `Bought`

These communicate the source of a requirement clearly and with little visual noise.

### Keep required quantity

Keep the required quantity beside each item, for example:

`20 needed`

This is one of the most useful pieces of information in the tree.

### Keep crafter information, but make the label clearer

For crafted requirements, keep the character information showing which character can perform the craft.

The current label `Character` is too generic.

Rename it to something clearer such as:

`Crafted by`

or another concise equivalent that makes its meaning obvious.

### Remove verbose per-node technical rows

The following rows should not be shown in the normal Crafting Resolution tree:

- `From stock 0`
- `Crafted for this requirement 1`
- `Bought 0`
- `Missing 0`
- `Producing recipe`
- recipe ID
- `Crafts run`
- `Produced in total`

These values make every node much taller while adding little useful information for the normal user.

The compact status label already communicates the important source state.

Technical values may remain available under an optional technical/debug view if they are still useful for diagnostics.

### Collapse the tree by default

The Crafting Resolution tree should be collapsed by default.

Nested recipe requirements should also be collapsed by default.

The user should explicitly expand a node when they want to inspect how that item is produced.

Example:

`Elonian Leather Square    1 needed`
`[Crafted]`
`Crafted by: Sat Anat...`
`▶ 4 ingredient requirements`

Only after clicking the disclosure control should the four ingredient requirements become visible.

The same behavior should apply recursively to nested crafted ingredients.

This is important because large recipes can otherwise create extremely long detail panels even when the user only wants a quick overview.

### Preserve useful economic information

Existing cost information such as cash cost, opportunity cost and effective cost may remain where it provides useful information and does not recreate the same excessive verbosity.

The planner/frontend implementation should review these remaining rows for usefulness and compactness rather than preserving technical fields solely because they currently exist.

## Why / Product Intent

The Crafting Resolution tree should help the user understand a recipe quickly.

The normal view should answer:

- What is needed?
- How much is needed?
- Do I already have it?
- Must it be crafted or bought?
- If crafted, who can craft it?
- What are its ingredients if I want to inspect them?

It should not expose the internal bookkeeping of the resolution algorithm by default.

The current implementation repeats many zero values and implementation-level fields for every node, which makes larger recipe trees extremely long and difficult to scan.

The desired behavior is progressive disclosure:

`compact summary first -> expand only when deeper detail is wanted`

## Constraints

- Preserve the existing backend-owned crafting resolution and tree structure.
- Do not reconstruct crafting logic in the frontend.
- Preserve the existing `From stock`, `Crafted` and `Bought` state information.
- Preserve required quantities.
- Preserve information identifying the character able to craft a crafted requirement, with a clearer label.
- Tree nodes and nested subtrees must be collapsed by default.
- Removing normal UI fields must not remove data from the backend contract if that data is still required elsewhere.
- Diagnostic/internal values may be moved into optional technical details instead of being deleted from the underlying model.
- Do not change crafting calculations as part of this request.

## Additional Context

The current tree can expand into a very long panel because every requirement displays multiple bookkeeping rows, including zero-value rows and recipe execution details.

The desired presentation is substantially more compact while preserving the information a user actually needs to understand the crafting path.

A separate concern was also observed in the selected-result detail: the UI shows a `THIS RECIPE IN THAT FRESH CALCULATION` block representing a separate fresh calculation whose values may differ from the table result. The planner should verify whether this second calculation and its presentation are still necessary and whether it belongs in the normal user-facing detail view.

## Planner Resolution

2026-09-27: RESOLVED as planning coverage, not implementation completion. Updated docs/DOMAIN_SPEC.md section 2.1.1 as the presentation requirement owner before creating agent/stories/STORY-WEB-016-compact-crafting-resolution-tree.md and adding it to agent/stories/BACKLOG.md. WEB-016 covers compact summaries, preserved sourcing/quantities/crafters, recursively collapsed ingredient groups, removal of normal bookkeeping and introductory prose, and review of economic rows. Reused agent/stories/STORY-WEB-015-profit-purchase-and-blocking-details.md for counted-craft purchases and concrete blocking causes. Reviewed the fresh calculation against docs/TARGET_ARCHITECTURE.md section 13 and agent/stories/STORY-WEB-007-profit-resolution-detail-view.md: preserve the established backend calculation supplying the tree and response association, while WEB-016 removes the redundant fresh-row summary from the normal view and keeps its basis concise or optional. No backend contract or calculation change is authorized by this presentation request.