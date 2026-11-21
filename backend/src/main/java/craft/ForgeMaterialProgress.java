package craft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Equal ingredient shares at each level; reads owned resources without spending or modifying account data. */
public final class ForgeMaterialProgress {
    public record Progress(double percent, boolean estimated, boolean partial) { }
    private record Coverage(double fraction, boolean ready, boolean estimated, boolean partial) { }
    private static final int COINS = 1;

    private final Map<Integer, Recipe> producers;
    /** Output items the resolved project actually acquires through a selected optional promotion. */
    private final Set<Integer> selectedPromotions;

    ForgeMaterialProgress(Map<Integer, Recipe> producers) { this(producers, Set.of()); }

    ForgeMaterialProgress(Map<Integer, Recipe> producers, Set<Integer> selectedPromotions) {
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
        Run run = new Run(inventory, ctx == null ? ForgeProjectPlanner.Context.NONE : ctx, wallet, reduce, noteProducer, daily);
        try {
            Coverage coverage = run.item(target.outputItemId, quantity, target, 0, Set.of(), null);
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
        private final Map<Integer, Long> currencies;
        private final boolean walletKnown;
        private final ForgeSettings.ReduceMode reduce;
        private final Recipe noteProducer;
        private final ForgeSettings.DailyMode daily;
        private int visited;
        private int promotionDepth;

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
            long spare = Math.min(surplus.getOrDefault(itemId, 0L), needed - used);
            if (spare > 0) { surplus.merge(itemId, -spare, Long::sum); used += spare; }
            return used;
        }

        private Coverage item(int itemId, long needed, Recipe selected, int depth, Set<Integer> path, String assignee) {
            if (needed <= 0) return new Coverage(1, true, false, false);
            if (depth > 0 && selected == null && promotionDepth == 0 && selectedPromotions.contains(itemId)) {
                Recipe promotion = producers.get(itemId);
                if (promotion != null && CraftingResolver.isPromotion(promotion) && !path.contains(promotion.recipeId)) {
                    Coverage promoted = promoted(promotion, needed, depth, path, assignee);
                    if (promoted != null) return promoted;
                }
            }
            long owned = take(itemId, needed, assignee, depth == 0);
            long missing = needed - owned;
            if (missing == 0) return new Coverage(1, true, false, false);
            double ownedFraction = (double) owned / needed;
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
            if (ready && !clover) {
                long surplus = ForgeProjectPlanner.mul(crafts, (long) recipe.outputCount) - missing;
                if (surplus > 0) this.surplus.merge(itemId, surplus, ForgeProjectPlanner::add);
            }
            return new Coverage(ownedFraction + (1 - ownedFraction) * sum / shares, ready, estimated, partial);
        }

        /**
         * The project's selected promotion: guaranteed minimum batches only, one external seed then minimum-only reuse.
         * An owned seed is diverted into the promotion (never also credited as finished output). Null = plain owned
         * stock already covers the need (or a bound was hit), so the generic path applies.
         */
        private Coverage promoted(Recipe recipe, long needed, int depth, Set<Integer> path, String assignee) {
            int itemId = recipe.outputItemId;
            long savedSurplus = surplus.getOrDefault(itemId, 0L);
            int mark = state.mark();
            boolean covered = take(itemId, needed, assignee, false) >= needed;
            state.rollbackTo(mark);
            surplus.put(itemId, savedSurplus);
            if (covered || ++visited > ForgeProjectPlanner.MAX_NODES || depth >= ForgeProjectPlanner.MAX_DEPTH) return null;
            String forge = ForgeProjectPlanner.assignForge(state, recipe);
            int seed = recipe.selfSeedUnits();
            int diverted = state.consumeInventoryWithBinding(itemId, seed, forge).total() >= seed ? seed : 0;
            if (diverted == 0) state.rollbackTo(mark);
            long owned = take(itemId, needed, assignee, false);
            long missing = needed - owned;
            double ownedFraction = (double) owned / needed;
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
                    if (extra > 0) surplus.merge(itemId, extra, ForgeProjectPlanner::add);
                }
                return new Coverage(ownedFraction + (1 - ownedFraction) * sum / shares, ready, false, partial);
            } finally {
                promotionDepth--;
            }
        }

        private Coverage currency(int currencyId, long needed, int depth, Set<Integer> path, String assignee) {
            long owned = takeCurrency(currencies, currencyId, needed);
            long missing = needed - owned;
            if (missing <= 0) return new Coverage(1, true, false, false);
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
