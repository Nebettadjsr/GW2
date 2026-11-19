package craft;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CraftingResolver {

    private final Map<Integer, Integer> directBuyUnitCache = new HashMap<>();
    private final Map<Integer, Integer> directSellUnitCache = new HashMap<>();
    private final Map<Integer, OwnedValue> ownedValueCache = new HashMap<>();

    /** Exact unit value: keep recipe batch fractions until the consumed quantity is known. */
    private record OwnedValue(BigInteger copper, BigInteger units) implements Comparable<OwnedValue> {
        static final OwnedValue ZERO = of(0);

        OwnedValue {
            BigInteger divisor = copper.gcd(units);
            copper = copper.divide(divisor);
            units = units.divide(divisor);
        }

        static OwnedValue of(int copper) {
            return new OwnedValue(BigInteger.valueOf(Math.max(copper, 0)), BigInteger.ONE);
        }

        OwnedValue add(OwnedValue other) {
            return new OwnedValue(copper.multiply(other.units).add(other.copper.multiply(units)),
                    units.multiply(other.units));
        }

        OwnedValue times(int quantity) {
            return new OwnedValue(copper.multiply(BigInteger.valueOf(quantity)), units);
        }

        OwnedValue per(int quantity) {
            return new OwnedValue(copper, units.multiply(BigInteger.valueOf(quantity)));
        }

        boolean isZero() { return copper.signum() == 0; }

        int costOf(int quantity) {
            // Round the complete consumed quantity up once, never each intermediate unit.
            BigInteger total = copper.multiply(BigInteger.valueOf(quantity));
            // Out-of-range values are an error (blocked as VALUE_RANGE by the caller), never clamped.
            return total.add(units).subtract(BigInteger.ONE).divide(units).intValueExact();
        }

        @Override public int compareTo(OwnedValue other) {
            return copper.multiply(other.units).compareTo(other.copper.multiply(units));
        }
    }

    /**
     * When true, every resolved requirement additionally records the explanation facts a semantic
     * trace needs (STORY-DOM-020, see {@link ResolvedNeed.NodeTrace}). Off for every table
     * calculation, whose resolution is unchanged and whose nodes carry no trace at all.
     */
    private final boolean tracing;

    public CraftingResolver() {
        this(false);
    }

    /** @param tracing see {@link #tracing}; only the single-craft explanation turns this on. */
    public CraftingResolver(boolean tracing) {
        this.tracing = tracing;
    }

    public ResolveResult resolveOneCraft(
            Recipe recipe,
            PlannerContext ctx,
            PlanState state
                                        ) {
        if (!ctx.isPerformable(recipe)) {
            return new ResolveResult(blockedNeed(recipe.outputItemId, recipe.outputCount,
                    BlockedReason.RECIPE_NOT_ALLOWED, recipe, null));
        }
        // DOMAIN_SPEC.md sections 28/30: the root is one execution of exactly this recipe. Owned or
        // bought finished output and alternative recipes for the same output never satisfy it; only
        // its ingredients go through ordinary owned/buy/craft acquisition.
        int mark = state.mark();
        ResolvedNeed craftNeed = tryCraft(recipe.outputItemId, recipe.outputCount, ctx, state, null, recipe);
        // Same node shape as an ordinary requirement resolved by crafting, so rows and trees agree.
        ResolvedNeed root = new ResolvedNeed(recipe.outputItemId, recipe.outputCount);
        if (craftNeed.getQtyBlocked() > 0) {
            state.rollbackTo(mark);
            if (tracing) root.traceForWrite().setCraftAttempt(craftNeed);
            root.setQtyBlocked(recipe.outputCount);
            root.setBlockedReason(craftNeed.getBlockedReason());
            root.determineMode();
            return new ResolveResult(root);
        }
        if (tracing) root.traceForWrite().setCraftAttempt(craftNeed);
        root.setQtyCrafted(craftNeed.getQtyCrafted());
        root.setBuyCostCopper(craftNeed.getBuyCostCopper());
        root.setOpportunityCostCopper(craftNeed.getOpportunityCostCopper());
        if (craftNeed.hasUnvaluedOwnedInput()) root.setUnvaluedOwnedInput(true);
        if (craftNeed.spendsUnpricedCurrency()) root.setSpendsUnpricedCurrency(true);
        craftNeed.unpricedCurrencyIds().forEach(root::addUnpricedCurrency);
        root.absorbWalletNotes(craftNeed);
        if (craftNeed.usesExpectedYield()) root.markUsesExpectedYield();
        for (ResolvedNeed child : craftNeed.getChildren()) root.addChild(child);
        root.determineMode();
        return new ResolveResult(root);
    }

    public ResolvedNeed resolveNeed(
            int itemId,
            int qtyRequested,
            PlannerContext ctx,
            PlanState state,
            boolean allowDirectBuy
                                   ) {
        return resolveNeed(itemId, qtyRequested, ctx, state, allowDirectBuy, null, null);
    }

    private ResolvedNeed resolveNeed(
            int itemId,
            int qtyRequested,
            PlannerContext ctx,
            PlanState state,
            boolean allowDirectBuy,
            String parentDiscipline,
            String assignedCharacter
                                   ) {
        ResolvedNeed result = new ResolvedNeed(itemId, qtyRequested);

        int reused = state.consumeSurplus(itemId, qtyRequested);
        result.setQtyCrafted(reused);
        int remaining = qtyRequested - reused;

        // 1) Use owned inventory first, if enabled
        // Material Protection: pre-existing owned copies of a protected item ID are unavailable in
        // every pool and at every depth; surplus produced in this plan (above) stays reusable.
        if (ctx.settings.useOwnMats && !ctx.settings.isProtected(itemId)) {
            // DOMAIN_SPEC.md section 11.1 / DQ-007: account-bound/soulbound-usable owned
            // quantity is consumed before ordinary tradable owned quantity and never charged
            // TP opportunity cost, since it cannot be sold on the Trading Post either way.
            // assignedCharacter (DOMAIN_SPEC.md section 2.2.1) further restricts soulbound
            // consumption to the character actually executing the step needing this item.
            PlanState.InventoryConsumption consumption =
                    state.consumeInventoryWithBinding(itemId, remaining, assignedCharacter);
            int usedFromInventory = consumption.total();
            result.setQtyFromInventory(usedFromInventory);

            // A direct TP sell quote is a property of the item, not of this requirement.
            int sellUnit = (consumption.usedSellable() > 0 || state.consumedSellableQuantity(itemId) > 0)
                    ? resolveDirectSellUnit(itemId, ctx.tp, ctx.settings) : 0;
            boolean directlyQuoted = sellUnit > 0;
            int oppCost = directlyQuoted ? Math.multiplyExact(consumption.usedSellable(), sellUnit) : 0;
            boolean unvalued = false;
            // DOMAIN_SPEC.md section 11.2: usable bound stock is not free, and sellable stock
            // without a direct quote uses the same fallback. Both pools share one cumulative
            // rounding basis (difference of rounded cumulative totals) so fractional per-unit
            // values never round up independently. Valuation only: nothing is bought or consumed.
            int fallbackUsed = consumption.usedBound() + (directlyQuoted ? 0 : consumption.usedSellable());
            if (fallbackUsed > 0) {
                OwnedValue fallback = resolveOwnedFallbackValue(itemId, ctx);
                int consumed = state.consumedBoundQuantity(itemId)
                        + (directlyQuoted ? 0 : state.consumedSellableQuantity(itemId));
                oppCost = Math.addExact(oppCost, Math.subtractExact(fallback.costOf(consumed),
                        fallback.costOf(consumed - fallbackUsed)));
                unvalued = fallback.isZero();
            }
            result.setOpportunityCostCopper(oppCost);

            if (unvalued) {
                // DOMAIN_SPEC.md section 11.2: this quantity was valued at zero because no value
                // could be established for it, which is not the same as it being free.
                result.setUnvaluedOwnedInput(true);
                if (tracing) result.traceForWrite().markUnvaluedNonTradable();
            }

            remaining -= usedFromInventory;
        }

        // fully satisfied by inventory
        if (remaining <= 0) {
            result.determineMode();
            return result;
        }

        // 2) Evaluate direct buy on a COPY of state
        CandidateEval buyEval = null;
        boolean buyPriceUnavailable = false;
        boolean buyOutOfRange = false;
        if (ctx.settings.allowBuying && allowDirectBuy) {
            int buyUnit = resolveDirectBuyUnit(itemId, ctx.tp, ctx.settings);
            long buyCost = (long) remaining * buyUnit;
            if (buyUnit > 0 && buyCost > Integer.MAX_VALUE) {
                // Exact wide product outside the supported copper range: this purchase is unavailable.
                buyOutOfRange = true;
            } else if (buyUnit > 0) {
                ResolvedNeed buyNeed = new ResolvedNeed(itemId, remaining);
                buyNeed.setQtyBought(remaining);
                buyNeed.setBuyCostCopper((int) buyCost);
                buyNeed.determineMode();

                buyEval = new CandidateEval(
                        buyNeed,
                        buyNeed.getBuyCostCopper()
                );
            } else {
                // DOMAIN_SPEC.md section 21: no usable TP quote is not the same as a free item.
                buyPriceUnavailable = true;
            }
        }

        // 3) Evaluate craft speculatively: performed on the live state and undone below if the
        //    craft is not the candidate finally chosen (this used to run on a full copy of state).
        CandidateEval craftEval = null;
        int craftMark = state.mark();
        ResolvedNeed craftNeed = tryCraft(itemId, remaining, ctx, state, parentDiscipline);
        if (craftNeed != null) {
            craftEval = new CandidateEval(craftNeed);
        } else {
            state.rollbackTo(craftMark);
            craftMark = -1;
        }

        // 4) Choose better candidate
        CandidateEval chosen = chooseBetterCandidate(buyEval, craftEval, ctx);

        if (chosen != null) {
            if (chosen.isStateCandidate()) {
                // The craft attempt is already applied to state; keeping it is the whole point.
                assert craftMark >= 0;
            } else {
                // Buying won: undo the speculative craft before charging the purchase.
                if (craftMark >= 0) state.rollbackTo(craftMark);

                state.addBuyCost(chosen.extraBuyCost);

            }

            ResolvedNeed chosenNeed = chosen.need;

            if (tracing && chosen.isStateCandidate()) {
                // Only the accepted craft is linked; a craft that lost to buying was rolled back
                // above and its node is unreachable from here.
                result.traceForWrite().setCraftAttempt(chosenNeed);
            }

            result.setQtyCrafted(reused + chosenNeed.getQtyCrafted());
            result.setQtyBought(chosenNeed.getQtyBought());
            result.setQtyBlocked(chosenNeed.getQtyBlocked());
            result.setBuyCostCopper(Math.addExact(result.getBuyCostCopper(), chosenNeed.getBuyCostCopper()));
            result.setOpportunityCostCopper(Math.addExact(result.getOpportunityCostCopper(),
                    chosenNeed.getOpportunityCostCopper()));
            if (chosenNeed.hasUnvaluedOwnedInput()) result.setUnvaluedOwnedInput(true);
            if (chosenNeed.spendsUnpricedCurrency()) result.setSpendsUnpricedCurrency(true);
            chosenNeed.unpricedCurrencyIds().forEach(result::addUnpricedCurrency);
            result.absorbWalletNotes(chosenNeed);
            if (chosenNeed.usesExpectedYield()) result.markUsesExpectedYield();

            if (chosenNeed.getBlockedReason() != BlockedReason.NONE) {
                result.setBlockedReason(chosenNeed.getBlockedReason());
            }

            for (ResolvedNeed child : chosenNeed.getChildren()) {
                result.addChild(child);
            }
        } else {
            // Neither candidate is usable: the speculative craft leaves no trace, exactly as the
            // discarded trial copy did before.
            if (craftMark >= 0) state.rollbackTo(craftMark);

            if (tracing && craftEval != null) {
                // ARCHITECTURE.md section 7: a blocked requirement still shows the
                // ingredient requirements of the craft path that was attempted for it. The attempt's
                // state changes are rolled back above; only its explanatory facts survive, and this
                // node's own quantities and costs below remain those of the blocked requirement.
                result.traceForWrite().setCraftAttempt(craftEval.need);
            }

            result.setQtyBlocked(remaining);

            BlockedReason craftReason = craftEval == null ? BlockedReason.NONE : craftEval.need.getBlockedReason();
            if (!ctx.settings.allowBuying && craftReason != BlockedReason.MATERIAL_LIMIT
                    && craftReason != BlockedReason.VALUE_RANGE
                    && craftReason != BlockedReason.WALLET_UNAVAILABLE
                    && craftReason != BlockedReason.INSUFFICIENT_CURRENCY) {
                // A nested limit/range failure is the real cause and is never masked by this.
                result.setBlockedReason(BlockedReason.BUYING_DISABLED);
            } else if (craftEval != null && craftEval.need.getBlockedReason() != BlockedReason.NONE) {
                result.setBlockedReason(craftEval.need.getBlockedReason());
            } else if (buyOutOfRange) {
                result.setBlockedReason(BlockedReason.VALUE_RANGE);
            } else if (buyPriceUnavailable) {
                result.setBlockedReason(BlockedReason.PRICE_UNAVAILABLE);
            } else {
                result.setBlockedReason(BlockedReason.NO_RECIPE);
            }
        }

        result.determineMode();
        return result;
    }

    private ResolvedNeed tryCraft(
            int itemId,
            int qtyRequested,
            PlannerContext ctx,
            PlanState state,
            String parentDiscipline
                                 ) {
        return tryCraft(itemId, qtyRequested, ctx, state, parentDiscipline, null);
    }

    /** @param fixedRecipe when non-null, the only recipe that may be executed (root craft). */
    private ResolvedNeed tryCraft(
            int itemId,
            int qtyRequested,
            PlannerContext ctx,
            PlanState state,
            String parentDiscipline,
            Recipe fixedRecipe
                                 ) {
        if (fixedRecipe == null) {
            return tryRecipeCandidates(itemId, qtyRequested, ctx, state, parentDiscipline);
        }
        Recipe recipe = fixedRecipe;

        boolean isDaily = DailyCrafts.isDailyOutput(itemId);

        // Daily outputs may be crafted only when the independent daily-crafts setting permits it.
        if (isDaily && !ctx.settings.allowDailyCrafts) {
            return blockedNeed(itemId, qtyRequested, BlockedReason.DAILY_LIMIT, recipe, null);
        }

        // Mystic Forge steps need no crafter: never assign (or invent) a character.
        if (!ctx.isCoordinated() || recipe.isMysticForge()) {
            return tryCraftAssigned(itemId, qtyRequested, recipe, ctx, state, null);
        }

        // DOMAIN_SPEC.md section 2.2.1: coordinated multi-character planning - a recipe may only
        // be performed by a character who has one of its disciplines at a sufficient rating.
        // Soulbound ingredients (below, via the assigned character's own PlanState.
        // characterBoundInventory) are then only usable by whichever eligible character actually
        // performs this specific craft step; the produced item itself remains a transferable
        // intermediate usable by any later step regardless of who crafted it.
        List<String> eligible = ctx.eligibleCharactersFor(recipe);
        if (eligible.isEmpty()) {
            return blockedNeed(itemId, qtyRequested, BlockedReason.RECIPE_NOT_ALLOWED, recipe, null);
        }

        // A single candidate is always "best" (isBetterCraftAttempt with no current best is always
        // true), so the speculative trial-copy-then-copyFrom dance below is unnecessary and,
        // being on the hot recursive path, materially expensive (STORY-PERF-001) - apply directly.
        if (eligible.size() == 1) {
            return tryCraftAssigned(itemId, qtyRequested, recipe, ctx, state, eligible.get(0));
        }

        // Each candidate is tried from the same starting point, then undone; the winner's net
        // effect is captured and re-applied at the end. Previously each candidate ran on its own
        // full copy of the state and the winning copy was copied back.
        ResolvedNeed best = null;
        PlanState.Delta bestEffect = null;
        int mark = state.mark();

        for (String character : eligible) {
            ResolvedNeed attempt = tryCraftAssigned(itemId, qtyRequested, recipe, ctx, state, character);
            if (isBetterCraftAttempt(attempt, best, ctx.minimizeBuyCost)) {
                best = attempt;
                bestEffect = state.captureDelta(mark);
            }
            state.rollbackTo(mark);
        }

        state.applyDelta(bestEffect);
        return best;
    }

    /**
     * Performs (or attempts) one craft-step batch of {@code recipe} for {@code itemId}, with
     * ingredient consumption attributed to {@code assignedCharacter} (may be {@code null} for
     * the single-character/legacy path, where every soulbound quantity already lives in the
     * shared {@link PlanState#boundInventory} rather than per-character).
     */
    private ResolvedNeed tryCraftAssigned(
            int itemId,
            int qtyRequested,
            Recipe recipe,
            PlannerContext ctx,
            PlanState state,
            String assignedCharacter
                                          ) {
        // cycle protection
        if (state.isVisiting(itemId)) {
            return blockedNeed(itemId, qtyRequested, BlockedReason.CYCLE_DETECTED, recipe, assignedCharacter);
        }

        boolean isDaily = DailyCrafts.isDailyOutput(itemId);

        state.beginVisiting(itemId);

        ResolvedNeed craftResult = new ResolvedNeed(itemId, qtyRequested);
        if (recipe.hasExpectedYield()) craftResult.markUsesExpectedYield();
        try {
            if (tracing) {
                ResolvedNeed.NodeTrace trace = craftResult.traceForWrite();
                trace.setSelectedRecipeId(recipe.recipeId);
                trace.setAssignedCharacter(assignedCharacter);
            }

            int timesNeeded = ceilDiv(qtyRequested, recipe.outputCount);
            int times = timesNeeded;

            // At most one daily craft operation is available in this planning state.
            if (isDaily) {
                int left = state.dailyLeft(itemId, 1);
                times = Math.min(timesNeeded, left);

                if (times <= 0) {
                    craftResult.setQtyBlocked(qtyRequested);
                    craftResult.setBlockedReason(BlockedReason.DAILY_LIMIT);
                    craftResult.determineMode();
                    return craftResult;
                }

                state.setDailyLeft(itemId, left - times);
            }

            int produced = Math.multiplyExact(times, recipe.outputCount);
            int qtySatisfied = Math.min(produced, qtyRequested);

            // Currency is spent from one account-wide wallet pool shared by nested and repeated crafts.
            // Unknown balance is not zero and not unlimited: the attempt is blocked, and the caller's
            // rollback restores the pool together with every other resource this attempt touched.
            for (CurrencyRequirement currency : recipe.currencies) {
                long needed = (long) currency.count() * times;
                // A fully published wallet without this currency's row is a known zero; only a null wallet is unknown.
                Integer balance = ctx.wallet == null ? null : ctx.wallet.getOrDefault(currency.currencyId(), 0);
                boolean enforce = ctx.walletMode == PlannerContext.WalletMode.ENFORCED;
                int currencyId = currency.currencyId();
                CurrencyProduction production = enforce && ctx.recipesByOutput.containsKey(
                        CurrencyProduction.forCurrency(currencyId).map(CurrencyProduction::itemId).orElse(-1))
                        ? CurrencyProduction.forCurrency(currencyId).orElse(null) : null;

                // Owned wallet units are used first, then units an earlier batch of this plan left over, then new
                // production batches. An unknown wallet contributes nothing: it is neither zero nor unlimited.
                long spent = state.currencySpent(currencyId);
                long ownedLeft = balance == null ? 0 : Math.max(0, balance - spent);
                long producedLeft = state.currencyProduced(currencyId) - Math.max(0, spent - (balance == null ? 0 : balance));
                // Hypothetical (IGNORED) evaluation bypasses affordability only: ownership is never invented, so
                // units beyond the captured shared balance stay missing and unpriced.
                long fromWallet = Math.min(needed, ownedLeft);
                long fromProduced = enforce ? Math.min(needed - fromWallet, Math.max(0, producedLeft)) : 0;
                long toProduce = needed - fromWallet - fromProduced;
                long craftedUnits = 0;
                boolean producerBlocked = false;
                BlockedReason producerReason = BlockedReason.NONE;

                if (toProduce > 0 && production != null) {
                    long batches = (toProduce + production.yield() - 1) / production.yield();
                    if (batches > Integer.MAX_VALUE) throw new ArithmeticException("currency batches out of range");
                    int batchCount = (int) batches;
                    if (!state.reserveDemand(production.itemId(), batchCount)) {
                        return limitBlocked(craftResult, qtyRequested, BlockedReason.MATERIAL_LIMIT);
                    }
                    ResolvedNeed producer = resolveNeed(production.itemId(), batchCount, ctx, state, false,
                            recipe.disciplinesText, assignedCharacter);
                    craftResult.addChild(producer);
                    if (producer.isFullySatisfied()) {
                        craftResult.addCostsFromChild(producer);
                        state.creditCurrency(currencyId, Math.multiplyExact((long) batchCount, production.yield()));
                        craftedUnits = toProduce;
                    } else {
                        producerBlocked = true;
                        producerReason = producer.getBlockedReason();
                    }
                }

                long covered = fromWallet + fromProduced + craftedUnits;
                craftResult.addCurrencySourcing(new ResolvedNeed.CurrencySourcing(currencyId,
                        Math.toIntExact(needed), Math.toIntExact(fromWallet), Math.toIntExact(fromProduced),
                        Math.toIntExact(craftedUnits), Math.toIntExact(needed - covered), balance));

                if (producerBlocked || (enforce && covered < needed)) {
                    if (producerReason == BlockedReason.MATERIAL_LIMIT || producerReason == BlockedReason.VALUE_RANGE) {
                        return limitBlocked(craftResult, qtyRequested, producerReason);
                    }
                    return limitBlocked(craftResult, qtyRequested,
                            ctx.wallet == null
                                    ? BlockedReason.WALLET_UNAVAILABLE : BlockedReason.INSUFFICIENT_CURRENCY);
                }
                // The shared pool tally is total consumption whatever its source (actual wallet/credit demand).
                state.spendCurrency(currencyId, needed, null, false);
                // Only wallet units keep a deferred (unpriced) valuation; crafted units are charged by their batches.
                // Wallet Research Notes are valued by the captured-quote estimate when one exists; every other
                // wallet currency (and Notes without a complete estimate) stays unpriced.
                if (!enforce && covered < needed) {
                    // Hypothetical missing units are neither wallet nor produced: they remain unpriced.
                    craftResult.addUnpricedCurrency(currencyId);
                }
                if (fromWallet > 0) {
                    ResearchNoteEstimate noteEstimate = currencyId == CurrencyProduction.RESEARCH_NOTE_CURRENCY_ID
                            ? ctx.researchNoteEstimate() : null;
                    if (noteEstimate != null && noteEstimate.available()) {
                        craftResult.addWalletNotes(Math.toIntExact(fromWallet), noteEstimate);
                    } else {
                        craftResult.addUnpricedCurrency(currencyId);
                    }
                }
            }

            boolean allChildrenSatisfied = true;
            BlockedReason unsatisfiedChildReason = BlockedReason.NONE;

            for (Ingredient ing : recipe.ingredients) {
                // Validate the multiplication and the accumulated per-item demand before anything
                // is consumed, priced, bought or descended into; an excess ends this attempt now.
                long childDemand = (long) ing.count * times;
                if (!state.reserveDemand(ing.itemId, childDemand)) {
                    return limitBlocked(craftResult, qtyRequested, BlockedReason.MATERIAL_LIMIT);
                }
                int childQtyNeeded = (int) childDemand;

                ResolvedNeed child = resolveNeed(ing.itemId, childQtyNeeded, ctx, state, true,
                        recipe.disciplinesText, assignedCharacter);
                craftResult.addChild(child);
                craftResult.addCostsFromChild(child);

                if (!child.isFullySatisfied()) {
                    allChildrenSatisfied = false;
                    if (unsatisfiedChildReason == BlockedReason.NONE) {
                        unsatisfiedChildReason = child.getBlockedReason();
                    }
                    if (child.getBlockedReason() == BlockedReason.MATERIAL_LIMIT
                            || child.getBlockedReason() == BlockedReason.VALUE_RANGE) {
                        unsatisfiedChildReason = child.getBlockedReason();
                        break; // this attempt can never be accepted; do not expand later siblings
                    }
                }
            }

            if (!allChildrenSatisfied) {
                craftResult.setQtyBlocked(qtyRequested);
                craftResult.setBlockedReason(
                        unsatisfiedChildReason != BlockedReason.NONE ? unsatisfiedChildReason : BlockedReason.NO_RECIPE
                );
                craftResult.determineMode();
                return craftResult;
            }

            craftResult.setQtyCrafted(qtySatisfied);
            state.addSurplus(itemId, produced - qtySatisfied);

            if (tracing) {
                craftResult.traceForWrite().setCraft(times, produced);
            }

            if (qtySatisfied < qtyRequested) {
                craftResult.setQtyBlocked(qtyRequested - qtySatisfied);
                craftResult.setBlockedReason(BlockedReason.DAILY_LIMIT);
            }

            craftResult.determineMode();
            return craftResult;

        } catch (ArithmeticException outOfRange) {
            // Some quantity, price or total left the supported range: this attempt is unavailable.
            // The caller rolls back every state change the attempt made.
            return limitBlocked(craftResult, qtyRequested, BlockedReason.VALUE_RANGE);
        } finally {
            state.endVisiting(itemId);
        }
    }

    private ResolvedNeed limitBlocked(ResolvedNeed craftResult, int qtyRequested, BlockedReason reason) {
        craftResult.setQtyCrafted(0);
        craftResult.setQtyBlocked(qtyRequested);
        craftResult.setBlockedReason(reason);
        craftResult.determineMode();
        return craftResult;
    }

    private ResolvedNeed tryRecipeCandidates(int itemId, int quantity, PlannerContext ctx,
                                             PlanState state, String parentDiscipline) {
        ResolvedNeed best = null;
        PlanState.Delta effect = null;
        boolean bestMatches = false;
        int mark = state.mark();
        List<Recipe> candidates = ctx.recipesByOutput.getOrDefault(itemId, List.of());
        if (candidates.size() == 1) {
            Recipe only = candidates.getFirst();
            if (ctx.isPerformable(only)) {
                return tryCraft(itemId, quantity, ctx, state, parentDiscipline, only);
            }
        }
        for (Recipe recipe : candidates) {
            if (!ctx.isPerformable(recipe)) continue;
            ResolvedNeed attempt = tryCraft(itemId, quantity, ctx, state, parentDiscipline, recipe);
            boolean matches = sharesDiscipline(parentDiscipline, recipe.disciplinesText);
            boolean preferDiscipline = best != null && attempt.isFullySatisfied()
                    && best.isFullySatisfied() && matches != bestMatches
                    && attempt.spendsUnpricedCurrency() == best.spendsUnpricedCurrency()
                    && (!ctx.minimizeBuyCost || attempt.getBuyCostCopper() == best.getBuyCostCopper());
            if (preferDiscipline ? matches : isBetterCraftAttempt(attempt, best, ctx.minimizeBuyCost)) {
                best = attempt;
                bestMatches = matches;
                effect = state.captureDelta(mark);
            }
            state.rollbackTo(mark);
        }
        state.applyDelta(effect);
        return best != null ? best : hasOnlyDisallowedRecipes(itemId, ctx)
                ? blockedNeed(itemId, quantity, BlockedReason.RECIPE_NOT_ALLOWED) : null;
    }

    private ResolvedNeed blockedNeed(int itemId, int qtyRequested, BlockedReason reason) {
        ResolvedNeed blocked = new ResolvedNeed(itemId, qtyRequested);
        blocked.setQtyBlocked(qtyRequested);
        blocked.setBlockedReason(reason);
        blocked.determineMode();
        return blocked;
    }

    /**
     * As {@link #blockedNeed(int, int, BlockedReason)}, for the blocked paths where a specific
     * recipe (and possibly a character) was identified before the path turned out to be unusable;
     * while tracing, that identity is part of the explanation.
     */
    private ResolvedNeed blockedNeed(int itemId, int qtyRequested, BlockedReason reason,
                                     Recipe recipe, String assignedCharacter) {
        ResolvedNeed blocked = blockedNeed(itemId, qtyRequested, reason);

        if (tracing) {
            ResolvedNeed.NodeTrace trace = blocked.traceForWrite();
            if (recipe != null) {
                trace.setSelectedRecipeId(recipe.recipeId);
            }
            trace.setAssignedCharacter(assignedCharacter);
        }

        return blocked;
    }

    /**
     * Compares two candidate character assignments for the same craft step: fully satisfied
     * beats partially satisfied, and among fully-satisfied candidates the lowest total effective
     * economic cost wins within the preferred valuation class (DOMAIN_SPEC.md section 22), otherwise
     * the least-blocked candidate wins. Budget retries compare immediate cash first.
     * Soulbound ingredients never carry an opportunity-cost difference between characters, so
     * this reduces to "prefer whichever eligible character can actually supply this step."
     */
    private boolean isBetterCraftAttempt(ResolvedNeed candidate, ResolvedNeed currentBest, boolean minimizeBuyCost) {
        if (currentBest == null) return true;

        boolean candidateSatisfied = candidate.isFullySatisfied();
        boolean bestSatisfied = currentBest.isFullySatisfied();

        if (candidateSatisfied != bestSatisfied) {
            return candidateSatisfied;
        }

        if (candidateSatisfied) {
            if (minimizeBuyCost && candidate.getBuyCostCopper() != currentBest.getBuyCostCopper()) {
                return candidate.getBuyCostCopper() < currentBest.getBuyCostCopper();
            }
            // Without a cash difference during retry, keep the ordinary valuation preference.
            if (candidate.spendsUnpricedCurrency() != currentBest.spendsUnpricedCurrency()) {
                return !candidate.spendsUnpricedCurrency();
            }
            return candidate.effectiveCostCopper() <= currentBest.effectiveCostCopper();
        }

        return candidate.getQtySatisfied() > currentBest.getQtySatisfied();
    }

    private CandidateEval chooseBetterCandidate(CandidateEval buyEval, CandidateEval craftEval, PlannerContext ctx) {
        boolean buyValid = buyEval != null
                && buyEval.need != null
                && buyEval.need.getQtyBlocked() == 0;

        boolean craftValid = craftEval != null
                && craftEval.need != null
                && craftEval.need.getQtyBlocked() == 0;

        if (buyValid && craftValid) {
            if (ctx.minimizeBuyCost && craftEval.need.getBuyCostCopper() != buyEval.need.getBuyCostCopper()) {
                return craftEval.need.getBuyCostCopper() < buyEval.need.getBuyCostCopper() ? craftEval : buyEval;
            }
            // An unpriced-currency craft is not proven cheaper than a fully priced purchase: buying wins.
            if (craftEval.need.spendsUnpricedCurrency()) return buyEval;
            // DOMAIN_SPEC.md section 22: choose the lowest total effective economic cost
            // (cash + opportunity cost), not the lowest immediate cash cost.
            return craftEval.need.effectiveCostCopper() <= buyEval.need.effectiveCostCopper()
                   ? craftEval
                   : buyEval;
        }

        if (craftValid) return craftEval;
        if (buyValid) return buyEval;

        return null;
    }

    private int ceilDiv(int a, int b) {
        return (int) (((long) a + b - 1) / b);
    }

    public int resolveDirectBuyUnit(
            int itemId,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings
                                   ) {

        Integer cached = directBuyUnitCache.get(itemId);
        if (cached != null) return cached;

        PriceQuote q = tp.get(itemId);
        if (q == null) {
            directBuyUnitCache.put(itemId, 0);
            return 0;
        }

        Integer v = settings.listingBuy ? q.buyUnit : q.sellUnit;
        int result = v == null ? 0 : v;

        directBuyUnitCache.put(itemId, result);
        return result;
    }

    /**
     * DOMAIN_SPEC.md 11.2's fallback for an owned item without a usable Trading Post side:
     * calculated crafting cost, then vendor sell value. Zero means unvalued, never free.
     */
    private OwnedValue resolveOwnedFallbackValue(int itemId, PlannerContext ctx) {
        OwnedValue cached = ownedValueCache.get(itemId);
        if (cached != null) return cached;
        OwnedValue value = fallbackValue(itemId, ctx, new HashSet<>());
        ownedValueCache.put(itemId, value);
        return value;
    }

    /** Crafting cost, else vendor value, else 0. */
    private OwnedValue fallbackValue(int itemId, PlannerContext ctx, Set<Integer> visiting) {
        OwnedValue crafted = craftingUnitValue(itemId, ctx, visiting);
        if (!crafted.isZero()) return crafted;
        return OwnedValue.of(ctx.vendorValues.getOrDefault(itemId, 0));
    }

    /** Compare real purchase/crafting routes recursively; vendor sell values are not buy offers. */
    private OwnedValue craftingUnitValue(int itemId, PlannerContext ctx, Set<Integer> visiting) {
        if (!visiting.add(itemId)) return OwnedValue.ZERO;
        try {
            OwnedValue best = OwnedValue.ZERO;
            for (Recipe recipe : ctx.recipesByOutput.getOrDefault(itemId, List.of())) {
                // A currency-consuming route has no complete gold cost: never value it as if currency were free.
                if (!recipe.currencies.isEmpty()) continue;
                OwnedValue total = OwnedValue.ZERO;
                boolean valued = true;
                for (Ingredient ing : recipe.ingredients) {
                    OwnedValue unit = OwnedValue.of(resolveDirectBuyUnit(ing.itemId, ctx.tp, ctx.settings));
                    if (visiting.contains(ing.itemId)) {
                        // Cyclic route: only a real purchase price is independent of the cycle;
                        // crafting or vendor fallback of an item already being valued is rejected.
                        if (unit.isZero()) { valued = false; break; }
                        total = total.add(unit.times(ing.count));
                        continue;
                    }
                    OwnedValue crafted = craftingUnitValue(ing.itemId, ctx, visiting);
                    if (!crafted.isZero() && (unit.isZero() || crafted.compareTo(unit) < 0)) unit = crafted;
                    if (unit.isZero()) unit = OwnedValue.of(ctx.vendorValues.getOrDefault(ing.itemId, 0));
                    if (unit.isZero()) { valued = false; break; }
                    total = total.add(unit.times(ing.count));
                }
                if (!valued || recipe.outputCount <= 0) continue;
                OwnedValue unitCost = total.per(recipe.outputCount);
                if (!unitCost.isZero() && (best.isZero() || unitCost.compareTo(best) < 0)) best = unitCost;
            }
            return best;
        } finally {
            visiting.remove(itemId);
        }
    }

    public int resolveDirectSellUnit(
            int itemId,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings
                                    ) {

        Integer cached = directSellUnitCache.get(itemId);
        if (cached != null) return cached;

        PriceQuote q = tp.get(itemId);
        if (q == null) {
            directSellUnitCache.put(itemId, 0);
            return 0;
        }

        Integer v = settings.listingSell ? q.sellUnit : q.buyUnit;
        int result = v == null ? 0 : v;

        directSellUnitCache.put(itemId, result);
        return result;
    }


    /**
     * DOMAIN_SPEC.md section 42: distinguishes "no recipe exists for this item at all"
     * (NO_RECIPE) from "recipe(s) exist but every candidate is filtered out by
     * ctx.allowedRecipeIds" (RECIPE_NOT_ALLOWED).
     */
    private boolean hasOnlyDisallowedRecipes(int itemId, PlannerContext ctx) {
        List<Recipe> list = ctx.recipesByOutput.get(itemId);
        if (list == null || list.isEmpty()) {
            return false;
        }

        for (Recipe recipe : list) {
            if (ctx.isPerformable(recipe)) {
                return false;
            }
        }

        return true;
    }

    private boolean sharesDiscipline(String a, String b) {
        if (a == null || b == null) {
            return false;
        }

        Set<String> disciplinesA = splitDisciplines(a);
        for (String discipline : splitDisciplines(b)) {
            if (disciplinesA.contains(discipline)) {
                return true;
            }
        }

        return false;
    }

    private Set<String> splitDisciplines(String disciplinesText) {
        Set<String> result = new HashSet<>();
        for (String part : disciplinesText.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static class CandidateEval {
        final ResolvedNeed need;
        /** True for the craft candidate, whose effect is already applied to the live PlanState. */
        final boolean stateCandidate;
        final int extraBuyCost;

        CandidateEval(ResolvedNeed need) {
            this.need = need;
            this.stateCandidate = true;
            this.extraBuyCost = 0;
        }

        CandidateEval(ResolvedNeed need, int extraBuyCost) {
            this.need = need;
            this.stateCandidate = false;
            this.extraBuyCost = extraBuyCost;
        }

        boolean isStateCandidate() {
            return stateCandidate;
        }
    }

}
