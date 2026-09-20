# UD-002 — Ectoplasm Salvage fee-model ambiguity (DQ-011)

## Status

RESOLVED

## Decision Needed

Should the live `EctoView` Ectoplasm Salvage feature deduct the Trading Post sell fee from Crystalline Dust revenue before computing profit/cost-per-luck — matching the separate, UI-disconnected `Main.java` implementation (`DUST_PER_ECTO = 1.0`, 15% fee via `applySellFees`) — or keep its current no-fee behavior (`DUST_PER_ECTO = 0.75`, no fee)? One authoritative model must be chosen and recorded in `docs/DOMAIN_SPEC.md` as DQ-011.

## Why This Is Needed

`docs/KNOWN_PROBLEMS.md` §3.6 confirms two disagreeing implementations exist in the codebase today, and `docs/DOMAIN_SPEC.md` §25's fee exemption is scoped explicitly to "Crafting Profit," not to Ectoplasm Salvage — so the spec does not itself resolve which of the two existing behaviors (or a third) is correct. This is a domain-rule decision that materially changes calculated output; per `CLAUDE.md`'s Domain Rules ("never invent or reinterpret a domain rule to make an implementation easier"), it must be made explicitly rather than inferred.

## Context

- `docs/KNOWN_PROBLEMS.md` §3.6 (the confirmed conflict).
- `docs/DOMAIN_SPEC.md` §45–47 (Ectoplasm domain parameters), §25 (fee exemption scope), §51/§53 (the open-domain-question process this decision follows).
- Proposed identifier DQ-011, to be recorded in `docs/DOMAIN_SPEC.md` once resolved.

## Blocks

- Any future story that would change or unify `EctoView`'s or `Main.java`'s Ectoplasm Salvage calculation.
- No currently existing story under `agent/stories/` depends on this decision.

## External Input Possibly Required

- user preference (which behavior matches actual expectations)
- GW2 Wiki/API research (whether Trading Post fees apply to dust sales generally, independent of this specific feature)

## User Decision

`Main.java` is legacy code and may be removed.

The live Ectoplasm Salvage feature must display prices/revenue without deducting Trading Post selling fees.

The UI must show a one-time informational notice that Trading Post fees will still apply when the resulting items are actually sold.

## Resolution

DQ-011:

- `EctoView` is the authoritative Ectoplasm Salvage implementation.
- Do not subtract Trading Post selling fees from the displayed Ectoplasm Salvage price/revenue/profit calculation.
- Inform the user once in the UI that Trading Post fees are not included and will apply on sale.
- `Main.java` is obsolete legacy code and may be deleted.