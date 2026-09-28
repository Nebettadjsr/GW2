import craft.BlockedReason;
import craft.CraftResult;
import craft.CraftingSettings;
import craft.Ingredient;
import craft.PriceQuote;
import craft.Recipe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * STORY-APP-013: the crafting-profit table's "Total sell value" column and its sort path must show
 * the authoritative gross total the domain already computed
 * ({@code CraftResult.totalSellValueCopper}, DOMAIN_SPEC.md section 2.1.1), carried through
 * {@code CraftingProfitController.UiRow}, not a presentation-side
 * {@code revenueCopper x craftableCount} recomputation (STORY-DOM-023 Follow-up Finding F002).
 *
 * <p>The domain's own value is that same product today, so a fixture built from planner output
 * could not tell the two apart. Every result here therefore carries a supplied total that
 * deliberately differs from the product of its own item sell price and craftable count: if either
 * layer re-derives, these assertions fail rather than silently agreeing.
 *
 * <p>Plain unit test, no TestFX/database: {@code prepareRows},
 * {@link CraftingProfitView#toCraftRow} and {@link CraftingProfitView#rowComparator} are pure and
 * {@code CraftRow} is a property holder, so the carry-through and the sort path are checked without
 * a desktop session. The column's own cell rendering ("Unavailable" for a row whose calculation is
 * not available) is the shared coin cell and is unchanged by this story; what presentation needs
 * from the mapping - the flag reaching the row beside the supplied value - is asserted below.
 */
class CraftingProfitTotalSellValuePresentationTest {

    private static final int RECIPE_ID = 1;
    private static final int OUTPUT_ITEM_ID = 100;
    private static final Recipe RECIPE = new Recipe(
            RECIPE_ID, OUTPUT_ITEM_ID, 1, 0, "Chef", List.of(new Ingredient(200, 2)));
    private static final List<Recipe> RECIPES = List.of(RECIPE);

    /** No buying, so no quote can make the calculation unavailable; only the fixture decides. */
    private static final CraftingSettings SETTINGS =
            new CraftingSettings(true, false, 0, false, false, false);

    /** A result whose supplied total sell value is unrelated to revenueCopper * craftableCount. */
    private static CraftResult result(int craftableCount, int revenueCopper, int totalSellValueCopper) {
        return new CraftResult(OUTPUT_ITEM_ID, "Chef", craftableCount,
                Map.of(), Map.of(),
                /* buyCostCopper */ 10, /* matsSellValueCopper */ 20,
                revenueCopper, /* profitCopper */ 7, /* totalProfitCopper */ 35,
                totalSellValueCopper, /* tree */ null, BlockedReason.NONE);
    }

    private static CraftingProfitController.UiRow prepareOneRow(CraftResult result) {
        Map<Integer, PriceQuote> tp = Map.of(OUTPUT_ITEM_ID, new PriceQuote(400, 500));
        List<CraftingProfitController.UiRow> rows = new CraftingProfitController().prepareRows(
                RECIPES, RECIPES, Map.of(RECIPE_ID, result), Map.of(), tp, SETTINGS);
        assertEquals(1, rows.size());
        return rows.getFirst();
    }

    /** A displayed row whose supplied total sell value is unrelated to its own price times count. */
    private static CraftingProfitView.CraftRow craftRow(String name,
                                                        int craftableCount,
                                                        int revenueCopper,
                                                        int totalSellValueCopper) {
        return CraftingProfitView.toCraftRow(new CraftingProfitController.UiRow(
                RECIPE_ID, OUTPUT_ITEM_ID, name, "Chef",
                craftableCount, "",
                /* buyCostCopper */ 10, /* matsSellValueCopper */ 20,
                revenueCopper, /* profitCopper */ 7, /* totalProfitCopper */ 35,
                totalSellValueCopper, name.toLowerCase(), true));
    }

    private static List<String> namesInOrder(List<CraftingProfitView.CraftRow> rows, String sortMode) {
        List<CraftingProfitView.CraftRow> sorted = new ArrayList<>(rows);
        sorted.sort(CraftingProfitView.rowComparator(sortMode));
        return sorted.stream().map(CraftingProfitView.CraftRow::getOutputName).toList();
    }

    @Test
    void controllerCarriesTheDomainTotalSellValueRatherThanRecomputingIt() {
        CraftingProfitController.UiRow row = prepareOneRow(result(5, 30, 4242));

        assertEquals(4242, row.totalSellValueCopper,
                "the prepared row must carry the domain's own total sell value");
        assertNotEquals(row.revenueCopper * row.craftableCount, row.totalSellValueCopper,
                "fixture guard: the supplied total must differ from the old multiplication, "
                        + "or this test could not detect a recomputation");

        // Neighbouring numbers are untouched and still come straight from the same CraftResult.
        assertEquals(5, row.craftableCount);
        assertEquals(30, row.revenueCopper, "the item sell price stays the gross quote");
        assertEquals(7, row.profitCopper);
        assertEquals(35, row.totalProfitCopper);
        assertEquals(10, row.buyCostCopper);
        assertEquals(20, row.matsSellValueCopper);
    }

    @Test
    void rowMappingCarriesTheSuppliedTotalSellValueThroughToTheDisplayedRow() {
        CraftingProfitView.CraftRow row =
                CraftingProfitView.toCraftRow(prepareOneRow(result(5, 30, 4242)));

        assertEquals(4242, row.getTotalSellValueCopper(),
                "the displayed row must carry the application layer's total sell value");
        assertNotEquals(row.getRevenueCopper() * row.getCraftableCount(), row.getTotalSellValueCopper(),
                "fixture guard: the supplied total must differ from the old multiplication");

        // The "Total sell value" column binds to this property.
        assertEquals(4242, row.totalSellValueCopperProperty().get());
        assertEquals(5, row.getCraftableCount());
        assertEquals(30, row.getRevenueCopper());
        assertEquals(35, row.getTotalProfitCopper());
    }

    @Test
    void totalSellValueSortUsesSuppliedTotals() {
        // Under the old revenueCopper * craftableCount formula the order would be Low (5 * 30 =
        // 150), High (1 * 10 = 10): exactly the reverse of the supplied totals below.
        List<CraftingProfitView.CraftRow> rows = List.of(
                craftRow("Low", 5, 30, 1),
                craftRow("High", 1, 10, 900));

        assertEquals(List.of("High", "Low"), namesInOrder(rows, "Total sell value"));
    }

    @Test
    void totalSellValueSortStaysDescendingAndKeepsInputOrderOnTies() {
        List<CraftingProfitView.CraftRow> rows = List.of(
                craftRow("Middle", 1, 1, 50),
                craftRow("TiedFirst", 9, 9, 100),
                craftRow("TiedSecond", 2, 2, 100),
                craftRow("Lowest", 7, 7, 0));

        assertEquals(List.of("TiedFirst", "TiedSecond", "Middle", "Lowest"),
                namesInOrder(rows, "Total sell value"));
    }

    @Test
    void anUnavailableRowKeepsItsFlagBesideTheSuppliedTotal() {
        // No completed craft: CraftingResultPresentation marks the calculation unavailable, which
        // is what the coin cell renders as "Unavailable" instead of any amount.
        CraftingProfitController.UiRow row = prepareOneRow(result(0, 30, 777));

        assertEquals(777, row.totalSellValueCopper,
                "an unavailable row still carries the supplied total unchanged, never a re-derivation");
        assertFalse(row.calculationAvailable);

        CraftingProfitView.CraftRow craftRow = CraftingProfitView.toCraftRow(row);
        assertEquals(777, craftRow.getTotalSellValueCopper());
        assertFalse(craftRow.isCalculationAvailable(),
                "the displayed row keeps the flag the Total sell value cell reads");
    }
}
