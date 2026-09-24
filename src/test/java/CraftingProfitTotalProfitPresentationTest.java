import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * STORY-APP-011: the crafting-profit table's "Total profit" column and its two total-profit sort
 * paths must show the authoritative total the application layer already computed
 * ({@code CraftingProfitController.UiRow.totalProfitCopper}), not a presentation-side
 * {@code craftableCount x profitCopper} recomputation (KNOWN_PROBLEMS.md section 4.4, CH-E2).
 *
 * <p>Today the domain's own formula happens to be that same product, so a fixture built from real
 * results could never tell the two apart. Every row here therefore carries a supplied total that
 * deliberately differs from the product of its own count and per-craft profit: if presentation ever
 * recomputes, these assertions fail rather than silently agreeing.
 *
 * <p>Plain unit test, no TestFX/database: {@code CraftRow} is a property holder and
 * {@link CraftingProfitView#toCraftRow} / {@link CraftingProfitView#rowComparator} are pure, so the
 * mapping and both sort paths are checked without a desktop session.
 */
class CraftingProfitTotalProfitPresentationTest {

    /** A row whose supplied total is unrelated to craftableCount * profitCopper. */
    private static CraftingProfitController.UiRow uiRow(String name,
                                                        int craftableCount,
                                                        int profitCopper,
                                                        int totalProfitCopper) {
        return new CraftingProfitController.UiRow(
                1, 100, name, "Chef",
                craftableCount, "",
                /* buyCostCopper */ 10, /* matsSellValueCopper */ 20,
                /* revenueCopper */ 30, profitCopper, totalProfitCopper,
                name.toLowerCase(), true);
    }

    private static CraftingProfitView.CraftRow craftRow(String name,
                                                        int craftableCount,
                                                        int profitCopper,
                                                        int totalProfitCopper) {
        return CraftingProfitView.toCraftRow(uiRow(name, craftableCount, profitCopper, totalProfitCopper));
    }

    private static List<String> namesInOrder(List<CraftingProfitView.CraftRow> rows, String sortMode) {
        List<CraftingProfitView.CraftRow> sorted = new ArrayList<>(rows);
        sorted.sort(CraftingProfitView.rowComparator(sortMode));
        return sorted.stream().map(CraftingProfitView.CraftRow::getOutputName).toList();
    }

    @Test
    void rowMappingCarriesTheSuppliedTotalRatherThanRecomputingIt() {
        CraftingProfitController.UiRow source = uiRow("Widget", 5, 100, 4242);

        CraftingProfitView.CraftRow row = CraftingProfitView.toCraftRow(source);

        assertEquals(4242, row.getTotalProfitCopper(),
                "the displayed row must carry the application layer's total profit");
        assertNotEquals(row.getCraftableCount() * row.getProfitCopper(), row.getTotalProfitCopper(),
                "fixture guard: the supplied total must differ from the old multiplication, "
                        + "or this test could not detect a recomputation");

        // The "Total profit" column binds to this property; every other displayed number is
        // unchanged and still comes straight from the same UiRow.
        assertEquals(4242, row.totalProfitCopperProperty().get());
        assertEquals(5, row.getCraftableCount());
        assertEquals(100, row.getProfitCopper());
        assertEquals(30, row.getRevenueCopper());
        assertEquals(10, row.getBuyCostCopper());
        assertEquals(20, row.getMatsSellValueCopper());
    }

    @Test
    void totalProfitSortUsesSuppliedTotals() {
        // Under the old craftableCount * profitCopper formula the order would be Low (5 * 100 =
        // 500), High (1 * 10 = 10): exactly the reverse of the supplied totals below.
        List<CraftingProfitView.CraftRow> rows = List.of(
                craftRow("Low", 5, 100, 1),
                craftRow("High", 1, 10, 900));

        assertEquals(List.of("High", "Low"), namesInOrder(rows, "Total profit"));
    }

    @Test
    void defaultSortUsesSuppliedTotals() {
        List<CraftingProfitView.CraftRow> rows = List.of(
                craftRow("Low", 5, 100, 1),
                craftRow("High", 1, 10, 900));

        assertEquals(List.of("High", "Low"), namesInOrder(rows, "some unrecognised sort mode"));
    }

    @Test
    void totalProfitSortStaysDescendingAndKeepsInputOrderOnTies() {
        List<CraftingProfitView.CraftRow> rows = List.of(
                craftRow("Middle", 1, 1, 50),
                craftRow("TiedFirst", 9, 9, 100),
                craftRow("TiedSecond", 2, 2, 100),
                craftRow("Lowest", 7, 7, -5));

        assertEquals(List.of("TiedFirst", "TiedSecond", "Middle", "Lowest"),
                namesInOrder(rows, "Total profit"));
        assertEquals(List.of("TiedFirst", "TiedSecond", "Middle", "Lowest"),
                namesInOrder(rows, "some unrecognised sort mode"));
    }

    @Test
    void otherSortModesAreUnchanged() {
        List<CraftingProfitView.CraftRow> rows = List.of(
                craftRow("A", 1, 300, 1),
                craftRow("B", 9, 5, 2));

        assertEquals(List.of("B", "A"), namesInOrder(rows, "Max craftable count"));
        assertEquals(List.of("A", "B"), namesInOrder(rows, "Profit per item"));
        // Total sell value stays revenueCopper * craftableCount (both rows share revenue 30).
        assertEquals(List.of("B", "A"), namesInOrder(rows, "Total sell value"));
    }
}
