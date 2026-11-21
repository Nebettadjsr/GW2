package craft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Equal ingredient shares at each level; reads owned resources without spending or modifying account data. */
public final class ForgeMaterialProgress {
    public record Progress(double percent, boolean estimated, boolean partial) { }
    /** The plan's per-occurrence operation fact: acquired through the promotion, and whether it diverted an OWNED seed. */
    record Choice(boolean promoted, boolean ownedSeed) { }
    private record Coverage(double fraction, boolean ready, boolean estimated, boolean partial) { }
    private static final int COINS = 1;

    private final Map<Integer, Recipe> producers;
    /**
     * The resolved project's choice for each tree occurrence, keyed by the item path from the root (ancestor items then
     * the item itself) and listed in tree order: promoted = acquired through the selected optional promotion.
     */
    private final Map<java.util.List<Integer>, java.util.List<Choice>> selectedPromotions;

    ForgeMaterialProgress(Map<Integer, Recipe> producers) { this(producers, Map.of()); }

    ForgeMaterialProgress(Map<Integer, Recipe> producers, Map<java.util.List<Integer>, java.util.List<Choice>> selectedPromotions) {
        this.producers = producers;
        this.selectedPromotions = selectedPromotions;
    }

    Progress calculate(Recipe target, long quantity, ForgeProjectPlanner.Inventory inventory,
                       ForgeProjectPlanner.Context ctx, Map<Integer, Long> wallet) {
        return calculate(target, quantity, inventory, ctx, wallet, ForgeSettings.ReduceMode.YES, null, ForgeSettings.DailyMode.CRAFT);
    }

    /** @param reduce the project's owned-root policy; @param noteProducer the project's selected Note producer (null: default) */
    Progress calculate(Recipe target, long quantity, ForgeProjectPlanner.Inventory inventory,
                       ForgeProjectPlanner.Context ctx, Map<Integer, Long> wallet,
                       ForgeSettings.ReduceMode reduce, Recipe noteProducer, ForgeSettings.DailyMode daily) {
        ForgeProjectPlanner.Context context = ctx == null ? ForgeProjectPlanner.Context.NONE : ctx;
        Run run = new Run(inventory, context, wallet, reduce, noteProducer, daily);
        try {
            Coverage coverage = run.item(target.outputItemId, quantity, target, 0, Set.of(), null);
            // The plan aggregates Research Notes once and schedules their production AFTER every other stock debit. Pass 1
            // therefore defers Note production through the whole traversal, then produces the aggregate once; pass 2 replays
            // the identical traversal so each Note consumer shares that single result.
            Coverage notes = run.produceDeferredNotes(target.outputItemId);
            if (notes != null) {
                run = new Run(inventory, context, wallet, reduce, noteProducer, daily);
                run.noteResult = notes;
                coverage = run.item(target.outputItemId, quantity, target, 0, Set.of(), null);
            }
            double percent = coverage.ready() ? 100 : Math.min(99.9, Math.floor(coverage.fraction() * 1000) / 10);
            return new Progress(percent, coverage.estimated(), coverage.partial());
        } catch (ArithmeticException unsupported) {
            return new Progress(0, false, true);
        }
    }

    private final class Run {
        private final PlanState state;
        private final ForgeProjectPlanner.Context ctx;
        private final Map<Integer, Long> surplus = new HashMap<>(); // conserved craft surplus, not owned stock
        /** Readiness credit (0..units) of each item's surplus: a batch's surplus is only as ready as the batch that makes it. */
        private final Map<Integer, Double> surplusCredit = new HashMap<>();
        private double lastCredit; // readiness credit (in units) of the most recent take()
        private final Map<Integer, Long> currencies;
        private final boolean walletKnown;
        private final ForgeSettings.ReduceMode reduce;
        private final Recipe noteProducer;
        private final ForgeSettings.DailyMode daily;
        private int visited;
        private int promotionDepth;
        private long deferredNoteShortfall;
        private Coverage noteResult; // replay pass: the aggregate scheduled Note production
        private final java.util.ArrayList<Integer> itemPath = new java.util.ArrayList<>();
        private final Map<java.util.List<Integer>, Integer> occurrence = new HashMap<>();

        Run(ForgeProjectPlanner.Inventory inventory, ForgeProjectPlanner.Context ctx, Map<Integer, Long> wallet,
            ForgeSettings.ReduceMode reduce, Recipe noteProducer, ForgeSettings.DailyMode daily) {
            this.reduce = reduce;
            this.daily = daily;
            this.noteProducer = noteProducer;
            this.state = new PlanState(inventory.sellable(), inventory.accountBound(), inventory.characterBound());
            this.ctx = ctx;
            walletKnown = wallet != null;
            currencies = walletKnown ? new HashMap<>(wallet) : new HashMap<>();
        }

        /** Owned stock through the planner's binding pools for the step's assignee; then conserved surplus. */
        private long take(int itemId, long needed, String assignee, boolean root) {
            int ask = (int) Math.min(Integer.MAX_VALUE, needed);
            long used = root ? ForgeProjectPlanner.consumeRootOutput(state, reduce, itemId, needed)
                    : state.consumeInventoryWithBinding(itemId, ask, assignee).total();
            double credit = used;
            long have = surplus.getOrDefault(itemId, 0L);
            long spare = Math.min(have, needed - used);
            if (spare > 0) {
                double share = surplusCredit.getOrDefault(itemId, (double) have) * spare / have;
                surplus.merge(itemId, -spare, Long::sum);
                surplusCredit.merge(itemId, -share, Double::sum);
                credit += share;
                used += spare;
            }
            lastCredit = credit;
            return used;
        }

        /** Adds planned batch surplus whose readiness equals the producing batch's coverage fraction (1 = fully ready). */
        private void addSurplus(int itemId, long units, double fraction) {
            surplus.merge(itemId, units, ForgeProjectPlanner::add);
            surplusCredit.merge(itemId, units * fraction, Double::sum);
        }

        private Coverage item(int itemId, long needed, Recipe selected, int depth, Set<Integer> path, String assignee) {
            if (needed <= 0) return new Coverage(1, true, false, false);
            itemPath.add(itemId);
            try {
                Choice choice = depth > 0 && selected == null && promotionDepth == 0 ? selectedOccurrence() : null;
                if (choice != null) {
                    Recipe promotion = producers.get(itemId);
                    if (promotion != null && CraftingResolver.isPromotion(promotion) && !path.contains(promotion.recipeId)) {
                        Coverage promoted = promoted(promotion, needed, depth, path, assignee, choice.ownedSeed());
                        if (promoted != null) return promoted;
                    }
                }
                return plainItem(itemId, needed, selected, depth, path, assignee);
            } finally {
                itemPath.removeLast();
            }
        }

        /** The project's promotion choice for THIS occurrence (path identity + visit order), null when not promoted. */
        private Choice selectedOccurrence() {
            java.util.List<Integer> key = java.util.List.copyOf(itemPath);
            java.util.List<Choice> choices = selectedPromotions.get(key);
            if (choices == null) return null;
            int index = occurrence.merge(key, 1, Integer::sum) - 1;
            return index < choices.size() && choices.get(index).promoted() ? choices.get(index) : null;
        }

        private Coverage plainItem(int itemId, long needed, Recipe selected, int depth, Set<Integer> path, String assignee) {
            long owned = take(itemId, needed, assignee, depth == 0);
            double credit = lastCredit;
            long missing = needed - owned;
            if (missing == 0) return credit >= owned - 1e-9 ? new Coverage(1, true, false, false)
                    : new Coverage(credit / needed, false, false, false); // met by a batch surplus that is not fully ready
            double ownedFraction = credit / needed;
            boolean surplusReady = credit >= owned - 1e-9;
            if (++visited > ForgeProjectPlanner.MAX_NODES || depth >= ForgeProjectPlanner.MAX_DEPTH) {
                return new Coverage(ownedFraction, false, false, true);
            }
            boolean clover = itemId == ForgeProjectPlanner.MYSTIC_CLOVER;
            Recipe recipe = clover ? ForgeReferenceRecipes.all().getFirst().asPlanningTarget()
                    : selected != null ? selected : producers.get(itemId);
            // Daily Buy (same predicate as the planner): the shortfall is purchased, so producer inputs do not count.
            if (!clover && depth > 0 && recipe != null && daily == ForgeSettings.DailyMode.BUY
                    && DailyCrafts.isDailyOutput(itemId)) return new Coverage(ownedFraction, false, false, false);
            if (recipe == null || recipe.hasExpectedYield()) return new Coverage(ownedFraction, false, false, false);
            if (path.contains(recipe.recipeId)) return new Coverage(ownedFraction, false, false, true);
            long crafts = clover ? Math.ceilDiv(ForgeProjectPlanner.mul(missing, 100L), ForgeProjectPlanner.CLOVER_PERCENT)
                    : Math.ceilDiv(missing, (long) recipe.outputCount);
            String stepAssignee = recipe.isMysticForge() ? ForgeProjectPlanner.assignForge(state, recipe)
                    : recipe.source == RecipeSource.ORDINARY ? ForgeProjectPlanner.assignOrdinary(state, ctx, recipe) : null;
            Set<Integer> next = new HashSet<>(path);
            next.add(recipe.recipeId);
            double sum = 0;
            int shares = 0;
            boolean ready = true, estimated = clover, partial = false;
            for (Ingredient ingredient : recipe.ingredients) {
                Coverage child = item(ingredient.itemId, ForgeProjectPlanner.mul(ingredient.count, crafts), null, depth + 1, next, stepAssignee);
                sum += child.fraction(); shares++;
                ready &= child.ready(); estimated |= child.estimated(); partial |= child.partial();
            }
            for (CurrencyRequirement currency : recipe.currencies) {
                Coverage child = currency(currency.currencyId(), ForgeProjectPlanner.mul(currency.count(), crafts), depth + 1, next, stepAssignee);
                sum += child.fraction(); shares++;
                ready &= child.ready(); estimated |= child.estimated(); partial |= child.partial();
            }
            if (recipe.vendorCopper > 0) {
                Coverage gold = currency(COINS, ForgeProjectPlanner.mul(recipe.vendorCopper, crafts), depth + 1, next, stepAssignee);
                sum += gold.fraction(); shares++;
                ready &= gold.ready(); partial |= gold.partial();
            }
            if (shares == 0) return new Coverage(ownedFraction, false, false, true);
            if (!clover) {
                // The scheduled batch makes its surplus whether or not every input is owned yet; later demand shares it
                // at the batch's coverage instead of simulating another batch (and another Note demand).
                long surplus = ForgeProjectPlanner.mul(crafts, (long) recipe.outputCount) - missing;
                if (surplus > 0) addSurplus(itemId, surplus, ready ? 1 : sum / shares);
            }
            // Weight by physical units: owned/surplus units at their credit, only the newly crafted missing units by the batch.
            return new Coverage(ownedFraction + (double) missing / needed * sum / shares, ready && surplusReady, estimated, partial);
        }

        /**
         * The project's selected promotion: guaranteed minimum batches only, one external seed then minimum-only reuse.
         * An owned seed is diverted into the promotion (never also credited as finished output). Null = plain owned
         * stock already covers the need (or a bound was hit), so the generic path applies.
         */
        private Coverage promoted(Recipe recipe, long needed, int depth, Set<Integer> path, String assignee, boolean ownedSeed) {
            int itemId = recipe.outputItemId;
            long savedSurplus = surplus.getOrDefault(itemId, 0L);
            double savedCredit = surplusCredit.getOrDefault(itemId, 0.0);
            int mark = state.mark();
            boolean covered = take(itemId, needed, assignee, false) >= needed;
            state.rollbackTo(mark);
            surplus.put(itemId, savedSurplus);
            surplusCredit.put(itemId, savedCredit);
            if (covered || ++visited > ForgeProjectPlanner.MAX_NODES || depth >= ForgeProjectPlanner.MAX_DEPTH) return null;
            String forge = ForgeProjectPlanner.assignForge(state, recipe);
            int seed = recipe.selfSeedUnits();
            // The plan's own seed strategy decides: owned seed diverted first, or owned units goal-first and the seed acquired.
            int diverted = ownedSeed && state.consumeInventoryWithBinding(itemId, seed, forge).total() >= seed ? seed : 0;
            if (diverted == 0) state.rollbackTo(mark);
            long owned = take(itemId, needed, assignee, false);
            double ownedFraction = lastCredit / needed;
            long missing = needed - owned;
            long batches = ForgeProjectPlanner.promotionBatches(recipe, missing);
            Set<Integer> next = new HashSet<>(path);
            next.add(recipe.recipeId);
            promotionDepth++;
            try {
                double sum = 0;
                int shares = 0;
                boolean ready = true, partial = false;
                for (Ingredient ingredient : recipe.ingredients) {
                    Coverage child;
                    if (ingredient.itemId == itemId) {
                        long external = ForgeProjectPlanner.seedExternalUnits(recipe, batches);
                        long rest = external - diverted;
                        Coverage more = rest > 0 ? item(itemId, rest, null, depth + 1, next, forge) : new Coverage(1, true, false, false);
                        child = new Coverage((diverted + more.fraction() * rest) / external, more.ready(), false, more.partial());
                    } else {
                        child = item(ingredient.itemId, ForgeProjectPlanner.mul(ingredient.count, batches), null, depth + 1, next, forge);
                    }
                    sum += child.fraction(); shares++;
                    ready &= child.ready(); partial |= child.partial();
                }
                for (CurrencyRequirement currency : recipe.currencies) {
                    Coverage child = currency(currency.currencyId(), ForgeProjectPlanner.mul(currency.count(), batches), depth + 1, next, forge);
                    sum += child.fraction(); shares++;
                    ready &= child.ready(); partial |= child.partial();
                }
                if (shares == 0) return new Coverage(ownedFraction, false, false, true);
                if (ready) {
                    long extra = ForgeProjectPlanner.promotionNet(recipe, batches) - missing;
                    if (extra > 0) addSurplus(itemId, extra, 1);
                }
                return new Coverage(ownedFraction + (double) missing / needed * sum / shares, ready && ownedFraction * needed >= owned - 1e-9, false, partial);
            } finally {
                promotionDepth--;
            }
        }

        /** Produces the aggregated, still-uncovered Notes once (after all other debits); null when none was deferred. */
        private Coverage produceDeferredNotes(int rootItemId) {
            if (deferredNoteShortfall <= 0) return null;
            var source = CurrencyProduction.forCurrency(CurrencyProduction.RESEARCH_NOTE_CURRENCY_ID).orElseThrow();
            itemPath.add(rootItemId);
            try {
                long containers = Math.ceilDiv(deferredNoteShortfall, (long) source.yield());
                return item(source.itemId(), containers, noteProducer, 1, Set.of(), null);
            } finally {
                itemPath.removeLast();
            }
        }

        private Coverage currency(int currencyId, long needed, int depth, Set<Integer> path, String assignee) {
            long owned = takeCurrency(currencies, currencyId, needed);
            long missing = needed - owned;
            if (missing <= 0) return new Coverage(1, true, false, false);
            if (walletKnown && currencyId == CurrencyProduction.RESEARCH_NOTE_CURRENCY_ID
                    && CurrencyProduction.forCurrency(currencyId).isPresent()) {
                double have = (double) owned / needed;
                if (noteResult == null) { // pass 1: demand only, production is scheduled once afterwards
                    deferredNoteShortfall = ForgeProjectPlanner.add(deferredNoteShortfall, missing);
                    return new Coverage(have, false, false, false);
                }
                return new Coverage(have + (1 - have) * noteResult.fraction(), noteResult.ready(), noteResult.estimated(),
                        !noteResult.ready() && noteResult.partial());
            }
            var production = CurrencyProduction.forCurrency(currencyId);
            if (production.isEmpty()) return new Coverage((double) owned / needed, false, false, !walletKnown);
            var source = production.get();
            long ownedContainers = take(source.itemId(), Math.ceilDiv(missing, (long) source.yield()), assignee, false);
            long credits = ForgeProjectPlanner.mul(ownedContainers, (long) source.yield());
            long used = Math.min(missing, credits);
            owned += used;
            missing -= used;
            if (credits > used) currencies.merge(currencyId, credits - used, ForgeProjectPlanner::add);
            if (missing == 0) return new Coverage(1, true, false, false);
            double fraction = (double) owned / needed;
            long containers = Math.ceilDiv(missing, (long) source.yield());
            Recipe producer = currencyId == CurrencyProduction.RESEARCH_NOTE_CURRENCY_ID ? noteProducer : null;
            Coverage crafted = item(source.itemId(), containers, producer, depth, path, assignee);
            if (crafted.ready()) {
                long surplus = ForgeProjectPlanner.mul(containers, (long) source.yield()) - missing;
                if (surplus > 0) currencies.merge(currencyId, surplus, ForgeProjectPlanner::add);
            }
            return new Coverage(fraction + (1 - fraction) * crafted.fraction(), crafted.ready(), crafted.estimated(),
                    !crafted.ready() && (!walletKnown || crafted.partial()));
        }
    }

    private static long takeCurrency(Map<Integer, Long> stock, int id, long needed) {
        long used = Math.min(needed, Math.max(0, stock.getOrDefault(id, 0L)));
        if (used > 0) stock.put(id, stock.get(id) - used);
        return used;
    }
}
