package application;

import craft.CraftResult;
import craft.CraftTraceNode;
import craft.PriceQuote;
import craft.Recipe;
import craft.SingleCraftExplanation;
import repo.ItemRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * What one resolution-detail operation produced for one selected recipe
 * (TARGET_ARCHITECTURE.md section 13.2/13.3): the recipe's normal row summary and the domain's
 * semantic explanation of one execution of it, both computed from the single set of inputs that
 * operation captured.
 *
 * <p>It carries calculation facts only. The wire envelope of section 13.3 - {@code consistency},
 * {@code calculatedAt}, the echoed {@code calculation} inputs and the DTO shapes - belongs to the
 * HTTP layer, and nothing here is JSON-, HTTP- or JavaFX-aware.
 *
 * <p>{@link #recipe()}, {@link #items()} and {@link #quotes()} are the inputs this operation
 * captured, carried so that a presentation layer can describe the recipe, name items and report
 * quotes from <em>this</em> operation's facts rather than reading anything again (section 13.2).
 *
 * <p>{@link Status} keeps the three outcomes section 13.3/13.4 require apart. A blocked
 * explanation is {@link Status#AVAILABLE}: it exists and carries its reasons, unlike
 * {@link Status#RESULT_UNAVAILABLE}, where there is no resolution result to explain at all, and
 * unlike {@link Status#RECIPE_NOT_IN_CALCULATION}, where this operation produced no row for the
 * recipe in the first place.
 *
 * @param recipeId    the recipe that was asked about; the root node's actually selected recipe may
 *                    differ (TARGET_ARCHITECTURE.md section 13.3, AR-003).
 * @param status      which of the three outcomes above applies.
 * @param recipe      the selected recipe as this operation's crafting graph holds it, or null when
 *                    {@code status} is {@link Status#RECIPE_NOT_IN_CALCULATION}.
 * @param row         this operation's freshly calculated summary for {@code recipeId}, or null
 *                    when {@code status} is {@link Status#RECIPE_NOT_IN_CALCULATION}.
 * @param explanation the domain explanation, or null in that same case.
 * @param items       the item metadata this operation captured, unfiltered.
 * @param quotes      the Trading Post quotes this operation captured, unfiltered.
 */
public record CraftingResolutionDetail(
        int recipeId,
        Status status,
        Recipe recipe,
        CraftResult row,
        SingleCraftExplanation explanation,
        Map<Integer, ItemRepository.ItemInfo> items,
        Map<Integer, PriceQuote> quotes
) {

    public enum Status {
        /** The recipe is not part of this operation's visible candidate set. */
        RECIPE_NOT_IN_CALCULATION,
        /** A row and an explanation exist; the explanation may itself be blocked. */
        AVAILABLE,
        /** A row exists, but there is no resolution result to explain. */
        RESULT_UNAVAILABLE
    }

    public CraftingResolutionDetail {
        items = Map.copyOf(items);
        quotes = Map.copyOf(quotes);
    }

    static CraftingResolutionDetail recipeNotInCalculation(int recipeId) {
        return new CraftingResolutionDetail(
                recipeId, Status.RECIPE_NOT_IN_CALCULATION, null, null, null, Map.of(), Map.of());
    }

    static CraftingResolutionDetail of(int recipeId,
                                       Recipe recipe,
                                       CraftResult row,
                                       SingleCraftExplanation explanation,
                                       Map<Integer, ItemRepository.ItemInfo> capturedItems,
                                       Map<Integer, PriceQuote> capturedQuotes) {
        return new CraftingResolutionDetail(
                recipeId,
                explanation.available() ? Status.AVAILABLE : Status.RESULT_UNAVAILABLE,
                recipe,
                row,
                explanation,
                capturedItems,
                capturedQuotes);
    }

    /**
     * Names for the items this operation's trace mentions, taken only from {@link #items()}: an item
     * whose captured metadata is missing or has no usable name is simply absent, never given a
     * fabricated one.
     */
    public Map<Integer, String> itemNames() {
        Map<Integer, String> names = new HashMap<>();
        if (explanation != null) {
            collectNames(explanation.root(), items, names);
        }
        return Map.copyOf(names);
    }

    private static void collectNames(CraftTraceNode node,
                                     Map<Integer, ItemRepository.ItemInfo> capturedItems,
                                     Map<Integer, String> out) {
        if (node == null) return;

        ItemRepository.ItemInfo info = capturedItems.get(node.itemId());
        if (info != null && info.name != null && !info.name.isBlank()) {
            out.put(node.itemId(), info.name);
        }

        for (CraftTraceNode child : node.children()) {
            collectNames(child, capturedItems, out);
        }
    }
}
