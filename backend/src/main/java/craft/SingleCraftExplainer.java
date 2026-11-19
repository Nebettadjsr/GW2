package craft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain entry point for explaining one execution of a selected recipe
 * (ARCHITECTURE.md section 7, DOMAIN_SPEC.md section 44).
 *
 * <p>It runs the authoritative resolver - the same {@link RecipeSimulator}/{@link CraftingResolver}
 * that produces the economic results, with the same eligibility, binding, valuation and
 * craft-versus-buy rules - with trace recording switched on, and reports the simulation's
 * <em>first</em> craft. There is no second resolver, no re-derivation of decisions from prices or
 * recipes, and no explanation of the craft that would follow an exhausted simulation.
 *
 * <p>Each call works only from the inputs it is handed: it copies them into its own planning state,
 * builds its own context, and loads nothing. The caller's inventory, budget and daily state are
 * never mutated, and no state is carried between calls - a {@code SingleCraftExplainer} instance
 * holds none.
 *
 * <p>Producing an explanation is opt-in per selected recipe. Table calculations go through
 * {@link CraftingPlanner}, whose resolver does not trace and whose rows therefore build no
 * semantic tree.
 */
public final class SingleCraftExplainer {

    private final Map<Integer, Integer> vendorValues;
    private Map<Integer, Integer> wallet; // null = unknown
    private int workLimit = PlanState.MAX_WORK_UNITS;

    /** Test seam: the same per-root work limit the table's planner ran under ({@link CraftingPlanner#withWorkLimit}). */
    SingleCraftExplainer withWorkLimit(int limit) {
        this.workLimit = limit;
        return this;
    }

    public SingleCraftExplainer() {
        this(Map.of());
    }

    /** An explainer valuing untradeable owned materials with these vendor values (DOMAIN_SPEC.md 11.2). */
    /** Explains against this account wallet (currency id to balance); {@code null} = unknown. */
    public SingleCraftExplainer withWallet(Map<Integer, Integer> wallet) {
        this.wallet = wallet == null ? null : Map.copyOf(wallet);
        return this;
    }

    public SingleCraftExplainer(Map<Integer, Integer> vendorValues) {
        this.vendorValues = Map.copyOf(vendorValues);
    }

    /**
     * Explains one craft on the individual-character path used by Crafting Discovery
     * (ARCHITECTURE.md section 7), whose inputs match
     * {@link CraftingPlanner#evaluateAll(List, Map, Map, Map, CraftingSettings, Set)}.
     *
     * @param recipe            the selected recipe; its output count is what the root requests.
     * @param recipes           every recipe the calculation may resolve through.
     * @param sellableInventory ordinary tradable owned quantity.
     * @param boundInventory    bound owned quantity, consumed first and never charged TP
     *                          opportunity cost (DOMAIN_SPEC.md section 11.1).
     */
    public SingleCraftExplanation explainIndividual(
            Recipe recipe,
            List<Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> boundInventory,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds
                                                   ) {

        PlannerContext ctx = new PlannerContext(
                CraftingPlanner.buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds).withVendorValues(vendorValues).withWallet(wallet);

        return explain(recipe, ctx, new PlanState(sellableInventory, boundInventory), 1, workLimit);
    }

    /**
     * Explains one craft on the coordinated-roster path used by Crafting Profit
     * (ARCHITECTURE.md section 7), whose inputs match
     * {@link CraftingPlanner#evaluateAllCoordinated(List, Map, Map, Map, List, Map,
     * CraftingSettings, Set)}. Each craft step is assigned to an eligible roster character exactly
     * as the table calculation assigns it, and the assignment is part of the explanation.
     */
    public SingleCraftExplanation explainCoordinated(
            Recipe recipe,
            List<Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> accountBoundInventory,
            Map<String, Map<Integer, Integer>> characterBoundInventory,
            List<CharacterCraftingProfile> roster,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds
                                                    ) {

        PlannerContext ctx = new PlannerContext(
                CraftingPlanner.buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds, roster).withVendorValues(vendorValues).withWallet(wallet);

        return explain(recipe, ctx,
                new PlanState(sellableInventory, accountBoundInventory, characterBoundInventory), 1, workLimit);
    }

    /**
     * Explains the complete output represented by a coordinated Profit row. The simulator replays
     * the selected recipe from the same initial pools and settings, stopping exactly after the
     * table's accepted craft count; it never attempts a further craft just to add a warning.
     * A zero-count row retains one rejected attempt so the item-specific reason remains available.
     */
    public SingleCraftExplanation explainCoordinatedResult(
            Recipe recipe,
            List<Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> accountBoundInventory,
            Map<String, Map<Integer, Integer>> characterBoundInventory,
            List<CharacterCraftingProfile> roster,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds,
            int countedCrafts
    ) {
        PlannerContext ctx = new PlannerContext(
                CraftingPlanner.buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds, roster).withVendorValues(vendorValues).withWallet(wallet);
        // The table already accepted these crafts under its per-root work limit, so the traced replay of that prefix is not
        // re-limited (tracing explores more than the table and must never shorten what the row counted). A zero-count
        // row replays its one attempt under the same limit so a WORK_LIMIT rejection stays blocked and finite.
        return explain(recipe, ctx,
                new PlanState(sellableInventory, accountBoundInventory, characterBoundInventory),
                Math.max(1, countedCrafts), countedCrafts > 0 ? Integer.MAX_VALUE : workLimit);
    }

    private SingleCraftExplanation explain(Recipe recipe, PlannerContext ctx, PlanState initialState,
                                           int acceptedCraftLimit, int limit) {
        initialState.resetWork(limit);
        RecipeSimulator simulator = new RecipeSimulator(
                new CraftingResolver(true), acceptedCraftLimit, true);
        RecipeSimulationResult simulation = simulator.simulateRecipe(recipe, ctx, initialState);

        Map<Integer, Recipe> recipesById = new java.util.HashMap<>();
        ctx.recipesByOutput.values().forEach(list -> list.forEach(r -> recipesById.put(r.recipeId, r)));
        recipesById.put(recipe.recipeId, recipe);

        List<ResolvedNeed> accepted = simulation.getAcceptedCrafts();
        if (!accepted.isEmpty()) {
            NoteLedger ledger = new NoteLedger();
            List<CraftTraceNode> roots = accepted.stream()
                    .map(craft -> toNode(craft, BlockedReason.NONE, recipesById, ledger))
                    .toList();
            CraftTraceNode root = withRootSeedReuse(combineNodes(roots, ledger), simulation.getReusedSeedUnits());
            // The selected root quantity is the net sellable output, coherent with the row and the source facts.
            return explanationOf(recipe, root, root.source() != null && root.source().reusedSeed() > 0
                    ? root.producedQuantity()
                    : Math.multiplyExact(recipe.outputCount, simulation.getCraftCount()));
        }

        // The first craft was attempted and rejected: its reasons are still an explanation
        // (ARCHITECTURE.md section 7), unlike the absence of a result.
        ResolvedNeed rejected = simulation.getBlockedAttempt();
        if (rejected != null) {
            return explanationOf(recipe, toNode(rejected, simulation.getBlockedReason(), recipesById, new NoteLedger()), recipe.outputCount);
        }

        return SingleCraftExplanation.unavailable(
                recipe.recipeId, recipe.outputItemId, recipe.outputCount);
    }

    private SingleCraftExplanation explanationOf(Recipe recipe, CraftTraceNode root, int outputQuantity) {
        return new SingleCraftExplanation(
                recipe.recipeId, recipe.outputItemId, outputQuantity, true, root);
    }

    /** Per-call exact wallet-Note units of each mapped node, so combined nodes round the aggregate once. */
    private static final class NoteLedger {
        record Entry(long units, ResearchNoteEstimate estimate) { }
        final Map<CraftTraceNode, Entry> entries = new java.util.IdentityHashMap<>();
    }

    private CraftTraceNode combineNodes(List<CraftTraceNode> nodes, NoteLedger ledger) {
        CraftTraceNode first = nodes.get(0);
        int requested = nodes.stream().mapToInt(CraftTraceNode::requestedQuantity).sum();
        int inventory = nodes.stream().mapToInt(CraftTraceNode::inventoryQuantity).sum();
        int crafted = nodes.stream().mapToInt(CraftTraceNode::craftedQuantity).sum();
        int bought = nodes.stream().mapToInt(CraftTraceNode::boughtQuantity).sum();
        int missing = nodes.stream().mapToInt(CraftTraceNode::missingQuantity).sum();
        int craftCount = nodes.stream().mapToInt(CraftTraceNode::craftCount).sum();
        int produced = nodes.stream().mapToInt(CraftTraceNode::producedQuantity).sum();

        LinkedHashSet<String> characters = new LinkedHashSet<>();
        for (CraftTraceNode node : nodes) {
            if (node.characterName() != null) characters.add(node.characterName());
        }
        LinkedHashSet<AcquisitionMethod> methods = new LinkedHashSet<>();
        LinkedHashSet<ResolutionState> states = new LinkedHashSet<>();
        LinkedHashSet<BlockedReason> reasons = new LinkedHashSet<>();
        nodes.forEach(node -> {
            methods.addAll(node.methods());
            states.addAll(node.states());
            reasons.addAll(node.blockedReasons());
        });

        List<CraftTraceNode> children = new ArrayList<>();
        // Children are aligned by identity (item, recipe, character, occurrence), not list position: an optional
        // child (e.g. a Research Note producer needed only by a later craft) must not shift the others.
        Map<String, List<CraftTraceNode>> variants = new LinkedHashMap<>();
        for (CraftTraceNode parent : nodes) {
            Map<String, Integer> seen = new java.util.HashMap<>();
            for (CraftTraceNode child : parent.children()) {
                String base = child.itemId() + ":" + child.recipeId() + ":" + child.characterName();
                int occurrence = seen.merge(base, 1, Integer::sum);
                variants.computeIfAbsent(base + "#" + occurrence, ignored -> new ArrayList<>()).add(child);
            }
        }
        variants.values().forEach(variant -> children.add(combineNodes(variant, ledger)));

        java.util.TreeMap<Integer, CraftTraceNode.CurrencyQuantity> currencyTotals = new java.util.TreeMap<>();
        for (CraftTraceNode node : nodes) {
            for (CraftTraceNode.CurrencyQuantity q : node.currencies()) {
                currencyTotals.merge(q.currencyId(), q, (a, b) -> new CraftTraceNode.CurrencyQuantity(
                        a.currencyId(), Math.addExact(a.quantity(), b.quantity()),
                        Math.addExact(a.fromWallet(), b.fromWallet()),
                        Math.addExact(a.fromProduced(), b.fromProduced()),
                        Math.addExact(a.crafted(), b.crafted()), Math.addExact(a.missing(), b.missing()),
                        a.walletBalance()));
            }
        }
        List<CraftTraceNode.CurrencyQuantity> currencies = List.copyOf(currencyTotals.values());

        CraftTraceNode combined = new CraftTraceNode(
                first.itemId(), requested, inventory, crafted, bought, missing,
                first.recipeId(), craftCount, produced,
                characters.isEmpty() ? null : String.join(", ", characters),
                List.copyOf(methods), List.copyOf(states), List.copyOf(reasons),
                sumIfAllKnown(nodes, CraftTraceNode::cashCostCopper),
                sumIfAllKnown(nodes, CraftTraceNode::opportunityCostCopper),
                combinedEffective(nodes, ledger),
                children, currencies, combineSource(nodes));
        long units = 0;
        ResearchNoteEstimate estimate = null;
        for (CraftTraceNode node : nodes) {
            NoteLedger.Entry e = ledger.entries.get(node);
            if (e == null) continue;
            units = Math.addExact(units, e.units());
            if (e.estimate() != null) estimate = e.estimate();
        }
        if (units > 0) ledger.entries.put(combined, new NoteLedger.Entry(units, estimate));
        return combined;
    }

    /**
     * Sum of the nodes' effective costs with the estimated wallet-Note value re-rounded once over the combined
     * Note quantity, instead of adding each execution's separately rounded-up Note cost.
     */
    private Integer combinedEffective(List<CraftTraceNode> nodes, NoteLedger ledger) {
        long total = 0;
        long units = 0;
        ResearchNoteEstimate estimate = null;
        for (CraftTraceNode node : nodes) {
            Integer amount = node.effectiveCostCopper();
            if (amount == null) return null;
            total += amount;
            NoteLedger.Entry e = ledger.entries.get(node);
            if (e != null && e.estimate() != null && e.units() > 0) {
                total -= e.estimate().costCopper(e.units());
                units += e.units();
                estimate = e.estimate();
            }
        }
        if (estimate != null) total += estimate.costCopper(units);
        return total < Integer.MIN_VALUE || total > Integer.MAX_VALUE ? null : (int) total;
    }

    private Integer sumIfAllKnown(List<CraftTraceNode> nodes,
                                  java.util.function.Function<CraftTraceNode, Integer> value) {
        int total = 0;
        for (CraftTraceNode node : nodes) {
            Integer amount = value.apply(node);
            if (amount == null) return null;
            total = Math.addExact(total, amount);
        }
        return total;
    }

    /**
     * Maps one resolved requirement onto its explanation.
     *
     * @param rejectionReason a reason the whole attempt was rejected above this requirement -
     *                        {@link BlockedReason#INSUFFICIENT_BUDGET} for a craft that resolved
     *                        but could not be afforded. {@link BlockedReason#NONE} everywhere else,
     *                        including for every child.
     */
    private CraftTraceNode toNode(ResolvedNeed need, BlockedReason rejectionReason, Map<Integer, Recipe> recipesById,
                                  NoteLedger ledger) {
        ResolvedNeed.NodeTrace trace = need.getTrace();
        ResolvedNeed craftAttempt = trace == null ? null : trace.getCraftAttempt();

        // The craft path's own facts live on the attempt node, except where the resolver blocked
        // the requirement itself before any attempt could be made and recorded them in place.
        ResolvedNeed.NodeTrace craftTrace = craftAttempt != null ? craftAttempt.getTrace() : trace;

        List<CraftTraceNode> children = new ArrayList<>();
        if (craftAttempt != null) {
            // Repeated items in different branches stay separate occurrences: each child is mapped
            // where it occurs, and a cycle ends in the resolver's finite CYCLE_DETECTED node.
            for (ResolvedNeed child : craftAttempt.getChildren()) {
                children.add(toNode(child, BlockedReason.NONE, recipesById, ledger));
            }
        }

        // An attempted craft that was rejected and rolled back contributed no units here, so its
        // execution count stays out of the explanation; its identity and requirements remain.
        boolean vendorBought = need.getVendorBatches() > 0;
        // A completed vendor purchase is an execution too (real batches and units), though it is never "crafted".
        boolean crafted = (need.getQtyCrafted() > 0 || vendorBought) && craftTrace != null;

        List<BlockedReason> blockedReasons = blockedReasonsOf(need, craftAttempt, rejectionReason);
        boolean complete = need.isFullySatisfied();

        // Currency units of this node's own craft path, from the recipe the resolver selected: the executions it
        // performed, or one attempt's worth (output-covering crafts) for a rejected/blocked attempt.
        Integer selectedRecipeId = craftTrace == null ? null : craftTrace.getSelectedRecipeId();
        Recipe selected = selectedRecipeId == null ? null : recipesById.get(selectedRecipeId);
        List<CraftTraceNode.CurrencyQuantity> currencies = List.of();
        if (selected != null && !selected.currencies.isEmpty()) {
            int executions = crafted ? craftTrace.getCraftCount()
                    : Math.max(1, ceilDiv(need.getQtyRequested(), Math.max(1, selected.outputCount)));
            Map<Integer, ResolvedNeed.CurrencySourcing> sourcing = new java.util.HashMap<>();
            for (ResolvedNeed.CurrencySourcing s : (craftAttempt != null ? craftAttempt : need).getCurrencySourcing()) {
                sourcing.put(s.currencyId(), s);
            }
            currencies = selected.currencies.stream().map(c -> {
                ResolvedNeed.CurrencySourcing s = sourcing.get(c.currencyId());
                if (s == null) {
                    return new CraftTraceNode.CurrencyQuantity(c.currencyId(),
                            Math.toIntExact((long) c.count() * executions));
                }
                return new CraftTraceNode.CurrencyQuantity(c.currencyId(), s.needed(), s.fromWallet(),
                        s.fromProduced(), s.crafted(), s.missing(), s.walletBalance());
            }).toList();
        }

        CraftTraceNode node = new CraftTraceNode(
                need.getItemId(),
                need.getQtyRequested(),
                need.getQtyFromInventory(),
                need.getQtyCrafted(),
                need.getQtyBought(),
                need.getQtyBlocked(),
                craftTrace == null ? null : craftTrace.getSelectedRecipeId(),
                crafted ? craftTrace.getCraftCount() : 0,
                crafted ? craftTrace.getProducedQuantity() : 0,
                craftTrace == null ? null : craftTrace.getAssignedCharacter(),
                methodsOf(need),
                statesOf(need, trace, blockedReasons, rejectionReason),
                blockedReasons,
                complete ? need.getBuyCostCopper() : null,
                complete ? need.getOpportunityCostCopper() : null,
                complete ? effectiveOrNull(need) : null,
                children,
                currencies,
                crafted ? sourceFacts(need, craftTrace, selected) : null
        );
        if (need.getWalletNoteUnits() > 0 && need.getNoteEstimate() != null) {
            ledger.entries.put(node, new NoteLedger.Entry(need.getWalletNoteUnits(), need.getNoteEstimate()));
        }
        return node;
    }

    /** Source facts of an executed Mystic Forge or vendor step, from what the resolver actually accepted. */
    private static CraftTraceNode.SourceFacts sourceFacts(ResolvedNeed need, ResolvedNeed.NodeTrace craftTrace,
                                                          Recipe selected) {
        if (selected == null || !(selected.isMysticForge() || selected.isVendor())) return null;
        int batches = craftTrace.getCraftCount();
        int net = craftTrace.getProducedQuantity();
        int used = selected.isVendor() ? need.getQtyBought() : need.getQtyCrafted();
        if (selected.isVendor()) {
            return new CraftTraceNode.SourceFacts(CraftTraceNode.SourceFacts.VENDOR, null, null, null,
                    batches, net, 0, net, null, Math.min(used, net), Math.max(0, net - used));
        }
        ExpectedYield y = selected.expectedYield;
        if (y == null) {
            return new CraftTraceNode.SourceFacts(CraftTraceNode.SourceFacts.MYSTIC_FORGE, null, null, null,
                    batches, net, 0, net, null, Math.min(used, net), Math.max(0, net - used));
        }
        int gross = need.getYieldProducedGross() > 0 ? need.getYieldProducedGross() : Math.multiplyExact(batches, y.min());
        int reused = need.getYieldReusedSeed();
        return yieldFacts(y, batches, gross, reused, Math.min(used, gross - reused));
    }

    private static CraftTraceNode.SourceFacts yieldFacts(ExpectedYield y, int batches, int gross, int reused, int used) {
        int net = gross - reused;
        java.math.BigDecimal expectedNet = y.mean().multiply(java.math.BigDecimal.valueOf(batches))
                .subtract(java.math.BigDecimal.valueOf(reused));
        return new CraftTraceNode.SourceFacts(CraftTraceNode.SourceFacts.MYSTIC_FORGE, y.mean(), y.min(), y.max(),
                batches, gross, reused, net, expectedNet, used, Math.max(0, net - used));
    }

    /** Sums per-batch source facts of aligned accepted nodes; null unless every node carries the same kind. */
    private static CraftTraceNode.SourceFacts combineSource(List<CraftTraceNode> nodes) {
        CraftTraceNode.SourceFacts first = nodes.get(0).source();
        if (first == null) return null;
        int batches = 0, gross = 0, reused = 0, net = 0, used = 0, surplus = 0;
        java.math.BigDecimal expected = first.expectedNet() == null ? null : java.math.BigDecimal.ZERO;
        for (CraftTraceNode n : nodes) {
            CraftTraceNode.SourceFacts f = n.source();
            if (f == null || !f.kind().equals(first.kind())) return null;
            batches = Math.addExact(batches, f.batches());
            gross = Math.addExact(gross, f.grossProduced());
            reused = Math.addExact(reused, f.reusedSeed());
            net = Math.addExact(net, f.guaranteedNet());
            used = Math.addExact(used, f.used());
            surplus = Math.addExact(surplus, f.surplus());
            if (expected != null) expected = f.expectedNet() == null ? null : expected.add(f.expectedNet());
        }
        return new CraftTraceNode.SourceFacts(first.kind(), first.expectedPerBatch(), first.min(), first.max(),
                batches, gross, reused, net, expected, used, surplus);
    }

    /**
     * The aggregate root simulation reuses a self-seed between accepted root batches, which no single batch trace
     * knows: remove those units from sellable output exactly once and recompute the expected net from them.
     */
    private static CraftTraceNode withRootSeedReuse(CraftTraceNode root, int reusedBetweenBatches) {
        CraftTraceNode.SourceFacts f = root.source();
        if (f == null || reusedBetweenBatches <= 0 || f.expectedPerBatch() == null) return root;
        int reused = Math.addExact(f.reusedSeed(), reusedBetweenBatches);
        int net = f.grossProduced() - reused;
        CraftTraceNode.SourceFacts adjusted = new CraftTraceNode.SourceFacts(f.kind(), f.expectedPerBatch(), f.min(),
                f.max(), f.batches(), f.grossProduced(), reused, net,
                f.expectedPerBatch().multiply(java.math.BigDecimal.valueOf(f.batches()))
                        .subtract(java.math.BigDecimal.valueOf(reused)),
                Math.min(f.used(), net), Math.max(0, net - Math.min(f.used(), net)));
        // The reused units were produced and consumed inside the plan: the root's requested/crafted/produced
        // quantities are the net sellable ones, keeping requested = inventory + crafted + bought + missing.
        int removed = Math.min(reusedBetweenBatches, root.craftedQuantity());
        return new CraftTraceNode(root.itemId(), root.requestedQuantity() - removed, root.inventoryQuantity(),
                root.craftedQuantity() - removed, root.boughtQuantity(), root.missingQuantity(), root.recipeId(),
                root.craftCount(), root.producedQuantity() - removed, root.characterName(), root.methods(), root.states(),
                root.blockedReasons(), root.cashCostCopper(), root.opportunityCostCopper(),
                root.effectiveCostCopper(), root.children(), root.currencies(), adjusted);
    }

    private static int ceilDiv(int a, int b) {
        return (int) (((long) a + b - 1) / b);
    }

    /** The effective cost, or null when cash plus opportunity cost is outside the int range. */
    private static Integer effectiveOrNull(ResolvedNeed need) {
        long exact = need.effectiveCostCopper();
        return exact < Integer.MIN_VALUE || exact > Integer.MAX_VALUE ? null : (int) exact;
    }

    private List<AcquisitionMethod> methodsOf(ResolvedNeed need) {
        List<AcquisitionMethod> methods = new ArrayList<>(3);
        if (need.getQtyFromInventory() > 0) methods.add(AcquisitionMethod.INVENTORY);
        if (need.getQtyCrafted() > 0) methods.add(AcquisitionMethod.CRAFT);
        if (need.getQtyBought() > 0) methods.add(AcquisitionMethod.BUY);
        return methods;
    }

    /**
     * Every explicit reason the resolver established for this requirement: its own, then - because
     * the resolver reports only one reason per node - the reason the craft path attempted for it
     * failed, which is what distinguishes a cycle, a daily limit or a disallowed recipe from the
     * generic reason recorded above it.
     */
    private List<BlockedReason> blockedReasonsOf(ResolvedNeed need,
                                                 ResolvedNeed craftAttempt,
                                                 BlockedReason rejectionReason) {
        List<BlockedReason> reasons = new ArrayList<>(3);

        addReason(reasons, need.getBlockedReason());
        if (craftAttempt != null) {
            addReason(reasons, craftAttempt.getBlockedReason());
        }
        addReason(reasons, rejectionReason);

        return reasons;
    }

    private void addReason(List<BlockedReason> reasons, BlockedReason reason) {
        if (reason != BlockedReason.NONE && !reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    private List<ResolutionState> statesOf(ResolvedNeed need,
                                           ResolvedNeed.NodeTrace trace,
                                           List<BlockedReason> blockedReasons,
                                           BlockedReason rejectionReason) {
        List<ResolutionState> states = new ArrayList<>(3);

        if (need.getQtyBlocked() > 0 || rejectionReason != BlockedReason.NONE) {
            states.add(ResolutionState.BLOCKED);
        }
        if (blockedReasons.contains(BlockedReason.PRICE_UNAVAILABLE)) {
            states.add(ResolutionState.PRICE_UNAVAILABLE);
        }
        if (blockedReasons.contains(BlockedReason.DAILY_LIMIT)) {
            states.add(ResolutionState.DAILY_LIMIT);
        }
        if (trace != null && trace.isUnvaluedNonTradable()) {
            states.add(ResolutionState.UNVALUED_NONTRADEABLE);
        }

        return states;
    }
}
