import application.CraftingProfitService;
import craft.CraftResult;
import craft.CraftingSettings;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import repo.DiscChoice;
import util.CoinUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * STORY-PERF-001 equivalence harness: records a complete digest of every {@link CraftResult} the
 * real Crafting Profit pipeline produces on the developer's real PostgreSQL database, across
 * several settings combinations, so a performance change to {@code craft.*} can be proven to leave
 * every computed value untouched on real data rather than only on synthetic unit fixtures.
 *
 * <p>Two modes, chosen by whether the digest file exists:
 * <ul>
 *   <li>file missing - computes and <em>writes</em> {@value #DIGEST_FILE} (the "before" reference);</li>
 *   <li>file present - computes again and <em>asserts</em> byte-identical output (the "after" check).</li>
 * </ul>
 * Force a fresh reference with {@code -Dgw2tool.perf.equivalence.rewrite=true}.
 *
 * <p>The digest deliberately includes the <em>iteration order</em> of the missing-to-buy maps, not
 * just their contents: {@code CraftingProfitController.summarizeMissing} shows only the first two
 * entries it encounters, so a reordering would be user-visible even though every number matched.
 *
 * <p>A deliberate before/after tool, not a standing regression test: the reference digest holds real
 * account-derived quantities, so it lives in {@code target/} and is never committed, and a run on a
 * clean {@code target/} only re-records. Run it once before a change to {@code craft.*} and once
 * after.
 *
 * <p>Reads only; never writes to the database. Excluded from the default {@code ./mvnw test} run by
 * its {@code IT} suffix (TEST_STRATEGY.md §34). Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitEquivalenceRealDbIT}
 */
class CraftingProfitEquivalenceRealDbIT {

    private static final String DIGEST_FILE = "target/perf001-equivalence-digest.txt";

    /** Settings combinations chosen to exercise each branch the optimization touches. */
    private static List<LabelledSettings> settingsMatrix() {
        return List.of(
                new LabelledSettings("view-defaults",
                        new CraftingSettings(true, false, CoinUtils.parseToCopper("1g"), false, false, true)),
                new LabelledSettings("own-mats+buying-1g",
                        new CraftingSettings(true, true, CoinUtils.parseToCopper("1g"), false, false, true)),
                new LabelledSettings("own-mats+buying-100g+listing+daily-craft",
                        new CraftingSettings(true, true, CoinUtils.parseToCopper("100g"), true, true, false)),
                new LabelledSettings("no-own-mats+no-buying",
                        new CraftingSettings(false, false, 0, false, false, true)),
                new LabelledSettings("no-own-mats+buying-10g",
                        new CraftingSettings(false, true, CoinUtils.parseToCopper("10g"), false, false, true)));
    }

    @Test
    void producesIdenticalResultsOnRealDatabase() throws Exception {
        Path digestPath = Path.of(DIGEST_FILE);
        boolean rewrite = Boolean.getBoolean("gw2tool.perf.equivalence.rewrite");
        boolean recording = rewrite || !Files.exists(digestPath);

        StringBuilder digest = new StringBuilder();
        for (LabelledSettings ls : settingsMatrix()) {
            long start = System.nanoTime();
            appendRun(digest, ls);
            System.out.println("digest run [" + ls.label() + "] took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
        }

        if (recording) {
            Files.createDirectories(digestPath.getParent());
            Files.writeString(digestPath, digest.toString());
            System.out.println("Recorded equivalence reference digest: " + digestPath.toAbsolutePath()
                    + " (" + digest.length() + " chars)");
            return;
        }

        String expected = Files.readString(digestPath);
        if (!expected.contentEquals(digest)) {
            Path actualPath = Path.of(DIGEST_FILE.replace(".txt", "-actual.txt"));
            Files.writeString(actualPath, digest.toString());
            fail("Crafting Profit results changed against the recorded real-database reference.\n"
                    + "  reference: " + digestPath.toAbsolutePath() + "\n"
                    + "  actual:    " + actualPath.toAbsolutePath() + "\n"
                    + "  first difference: " + firstDifference(expected, digest.toString()));
        }
        assertEquals(expected.length(), digest.length());
        System.out.println("Equivalence confirmed against recorded real-database reference ("
                + digest.length() + " chars, " + settingsMatrix().size() + " settings combinations).");
    }

    /** Runs the real use case for one settings combination and appends every produced value. */
    private void appendRun(StringBuilder out, LabelledSettings ls) throws Exception {
        CraftingProfitService service = new CraftingProfitService();
        CraftingProfitService.ProfitData data = service.reload(DiscChoice.all(), ls.settings());

        out.append("## settings=").append(ls.label()).append('\n');
        out.append("visibleRecipes=").append(data.visibleRecipes().size())
                .append(" allRecipes=").append(data.allRecipes().size())
                .append(" results=").append(data.resultsByRecipeId().size()).append('\n');

        List<Integer> recipeIds = new ArrayList<>(data.resultsByRecipeId().keySet());
        recipeIds.sort(null);

        for (int recipeId : recipeIds) {
            CraftResult cr = data.resultsByRecipeId().get(recipeId);
            out.append(recipeId)
                    .append('|').append(cr.outputItemId)
                    .append('|').append(cr.discipline)
                    .append('|').append(cr.craftableCount)
                    .append('|').append(cr.buyCostCopper)
                    .append('|').append(cr.matsSellValueCopper)
                    .append('|').append(cr.revenueCopper)
                    .append('|').append(cr.profitCopper)
                    .append('|').append(cr.totalProfitCopper)
                    .append('|').append(cr.blockedReason)
                    .append('|').append(inIterationOrder(cr.missingToBuy))
                    .append('|').append(inIterationOrder(cr.missingToBuyOne))
                    .append('\n');
        }

        // The user-visible status/missing text depends on map iteration order, so compare it too.
        CraftingProfitController controller = new CraftingProfitController();
        List<CraftingProfitController.UiRow> rows = controller.prepareRows(
                data.visibleRecipes(), data.allRecipes(), data.resultsByRecipeId(),
                data.items(), data.tp(), ls.settings());
        List<CraftingProfitController.UiRow> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Integer.compare(a.recipeId, b.recipeId));
        for (CraftingProfitController.UiRow row : sorted) {
            out.append("row|").append(row.recipeId)
                    .append('|').append(row.outputName)
                    .append('|').append(row.craftableCount)
                    .append('|').append(row.missingSummary)
                    .append('|').append(row.calculationAvailable)
                    .append('\n');
        }

        // The detail tree the user sees on row selection must also stay identical.
        List<Recipe> visible = new ArrayList<>(data.visibleRecipes());
        visible.sort((a, b) -> Integer.compare(a.recipeId, b.recipeId));
        int sampled = 0;
        for (Recipe r : visible) {
            if (sampled++ % 97 != 0) continue; // every 97th visible recipe: broad coverage, bounded size
            CraftResult detailed = service.getResultByRecipeId(r.recipeId);
            out.append("tree|").append(r.recipeId).append('|')
                    .append(renderTree(detailed == null ? null : detailed.tree)).append('\n');
        }
    }

    private static String inIterationOrder(Map<Integer, Integer> map) {
        if (map == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static String renderTree(craft.Node node) {
        if (node == null) return "null";
        StringBuilder sb = new StringBuilder();
        render(node, sb);
        return sb.toString();
    }

    private static void render(craft.Node node, StringBuilder sb) {
        sb.append(node.itemId).append('x').append(node.qty).append('[').append(node.action).append(']');
        if (node.children != null && !node.children.isEmpty()) {
            sb.append('(');
            for (craft.Node child : node.children) render(child, sb);
            sb.append(')');
        }
    }

    private static String firstDifference(String expected, String actual) {
        int limit = Math.min(expected.length(), actual.length());
        for (int i = 0; i < limit; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                int from = Math.max(0, i - 120);
                return "at char " + i + "\n    expected: ..." + expected.substring(from, Math.min(expected.length(), i + 120))
                        + "\n    actual:   ..." + actual.substring(from, Math.min(actual.length(), i + 120));
            }
        }
        return "one digest is a prefix of the other (expected " + expected.length()
                + " chars, actual " + actual.length() + ")";
    }

    private record LabelledSettings(String label, CraftingSettings settings) {
    }
}
