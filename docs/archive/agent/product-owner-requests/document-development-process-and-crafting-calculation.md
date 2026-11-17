# Product Owner Request

## Status

RESOLVED

## Title

Add concise development-process link to root README and create human-readable crafting calculation documentation

## Requested Change

Create better top-level documentation for two different audiences:

1. developers/users who want to understand how this project is being developed;
2. readers who want to understand the Crafting Profit / Crafting Discovery calculation without already knowing the full Guild Wars 2 crafting system.

### 1. Root README link to agent documentation

Update the root `README.md` with a short note near the top explaining that the project's AI-assisted development/orchestration process is documented separately.

Add a concise link to:

`agent/README.md`

The root README should not duplicate the contents of `agent/README.md`.

A short note is sufficient, for example conceptually:

> How this project is developed, including the AI orchestration workflow, is documented in `agent/README.md`.

Do not otherwise rewrite the root README for this request.

### 2. Human-readable crafting calculation documentation

Create dedicated Markdown documentation explaining how the crafting calculation works end-to-end.

The target reader is someone who may know Guild Wars 2 only superficially and does not already understand the project's crafting logic.

The documentation should explain the calculation in a human-readable way rather than as source-code documentation.

It should cover the major mechanics, assumptions, and decisions that influence the final crafting result and profit calculation.

This should include, where applicable:

- how recipes are selected;
- recipe unlock / availability rules;
- crafting discipline requirements;
- character-specific crafting eligibility;
- multi-character crafting;
- transferable intermediate items;
- soulbound / character-bound materials;
- account-wide materials;
- inventory / bank / character inventory usage;
- owned-material opportunity cost;
- buying missing materials;
- buy-vs-craft decisions;
- Trading Post prices;
- Trading Post fees;
- instant-buy / listing-buy behavior;
- instant-sell / listing-sell behavior;
- unavailable prices;
- blocked/unavailable crafting results;
- daily craft restrictions;
- recipe discovery where relevant;
- simulation limits such as the intentional 250-craft cap;
- how total profit and profit per item are derived;
- any important assumptions that affect the displayed result.

The documentation should explain not only what the current rules are, but why they matter to the calculation.

### Human readability

This documentation is intentionally allowed to duplicate information from authoritative project documents.

This is an explicit exception to the normal single-source-of-truth duplication rule.

Reason:

The purpose of these files is user/developer comprehension.

A reader should not need to open `DOMAIN_SPEC.md`, `TARGET_ARCHITECTURE.md`, multiple User Decisions, and story files just to understand one crafting calculation.

The authoritative documents still remain the source of truth.

The human-readable guide is a maintained explanation/summary of those rules.

### Structure

One file is acceptable if it remains readable.

If the content becomes too large, prefer a dedicated documentation folder, for example:

`docs/crafting/`

Possible structure:

- `README.md` — overview and calculation flow
- `CRAFTING_RULES.md` — Guild Wars 2 mechanics and project decisions
- `PROFIT_CALCULATION.md` — price/cost/profit calculation
- `GLOSSARY.md` — terms and concepts
- `FAQ.md` — common questions / surprising behavior

The exact split is up to the planner/implementation agent.

Prefer a small number of useful files over unnecessary fragmentation.

### Glossary / FAQ

Include a compact glossary and/or FAQ for concepts that may be unclear to someone unfamiliar with the project or Guild Wars 2 crafting.

Examples:

- What is a crafting discipline?
- What is a recipe unlock?
- What is soulbound vs account-bound?
- Why can multiple characters participate in one crafting tree?
- Why can't soulbound materials simply be pooled?
- What does "use own mats" mean?
- Why does an owned tradable material still have an opportunity cost?
- What is the difference between instant buy and listing buy?
- What is the difference between instant sell and listing sell?
- Why can a result be blocked instead of simply omitted?
- Why is crafting simulation capped at 250?

### Root README links

Update the root `README.md` with concise links to:

- `agent/README.md`
- the new crafting-calculation documentation entry point

Do not copy the detailed content into the root README.

### Ongoing maintenance

The crafting-calculation documentation must be treated as maintained project documentation.

When future work materially changes:

- crafting rules;
- calculation behavior;
- character handling;
- binding behavior;
- pricing;
- fees;
- profit calculation;
- blocked-result semantics;
- simulation limits;
- other user-visible crafting behavior;

the relevant human-readable crafting documentation should be updated as part of that work.

Add this expectation to the appropriate development/documentation instructions so future planner/implementation work knows to maintain it.

The human-readable guide must not silently drift away from the authoritative domain behavior.

## Why / Product Intent

The project contains complex crafting logic influenced by both Guild Wars 2 mechanics and project-specific product decisions.

At present, understanding why a result is produced may require reading multiple technical documents and implementation artifacts.

The goal is to provide a clear explanation that allows a technically interested reader, even without deep Guild Wars 2 crafting knowledge, to understand:

- what inputs affect a result;
- which rules are applied;
- why certain materials/characters can or cannot be used;
- how the crafting tree is evaluated;
- how the final profit is calculated.

The root README should then provide a simple entry point to both:

- how the software is being developed;
- how the central crafting calculation works.

## Constraints

- Keep the root README change small.
- Do not rewrite `agent/README.md`.
- Use Markdown.
- Prefer human-readable explanations over implementation-class/method walkthroughs.
- Authoritative project docs remain the source of truth.
- Duplication inside the human-readable crafting guide is explicitly allowed for clarity.
- Do not invent Guild Wars 2 mechanics or project rules; derive the guide from the existing authoritative documentation and resolved decisions.
- Clearly distinguish important project-specific choices from underlying Guild Wars 2 mechanics where useful.
- Keep the documentation maintainable rather than generating a huge unstructured wall of text.

## Planner Resolution

Recorded guide structure, explicit duplication exception and ongoing maintenance policy in `docs/TARGET_ARCHITECTURE.md` §35. Created `agent/stories/STORY-DOC-001-explain-crafting-calculation.md` and queued it in `agent/stories/BACKLOG.md` to produce the reader guide, root README links and discoverable development-instructions reminder. The story covers the requested mechanics, glossary/FAQ, source fidelity and arithmetic/link verification. Guide creation and README edits are planned work, not claimed completed.