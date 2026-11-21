package parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import craft.MysticForgeCatalog;
import craft.Recipe;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The bundled snapshot (generated from the captured Wiki data) and the strict runtime adapter. */
class MysticForgeCatalogLoaderTest {

    private static MysticForgeCatalog of(String recipes) throws Exception {
        return MysticForgeCatalogLoader.fromJson(new ObjectMapper().readTree(
                "{\"schemaVersion\":1,\"recipes\":[" + recipes + "]}"));
    }

    private static String rec(String forge, String extra) {
        return "{\"forgeId\":" + forge + ",\"outputItemId\":10,\"outputCount\":1,\"page\":\"A#recipe1\",\"revision\":5,"
                + "\"ingredients\":[[1,2],[3,4]]" + extra + "}";
    }

    private static Recipe byOutputPage(MysticForgeCatalog c, String page) {
        return c.recipes().stream().filter(r -> page.equals(c.provenance(r.recipeId).page())).findFirst().orElseThrow();
    }

    @Test
    void bundledCatalogIsBroadVerifiedAndSourceIdentified() {
        MysticForgeCatalog c = MysticForgeCatalogLoader.bundled();
        assertTrue(c.isUsable(), c.status());
        assertTrue(c.recipes().size() > 1000, "broad coverage, not a demo: " + c.recipes().size());
        assertEquals(c.recipes().size(), c.coverage().includedRecipes());
        assertEquals(2569, c.coverage().sourceRows());
        assertTrue(c.coverage().excludedByReason().getOrDefault(
                "source row lacks wiki forge id / output id / ingredient ids (older or custom page)", 0) >= 447);
        assertTrue(c.license().contains("GNU Free Documentation License 1.3"));
        Set<Integer> ids = new HashSet<>();
        for (Recipe r : c.recipes()) {
            assertTrue(r.recipeId < 0 && r.isMysticForge());
            assertTrue(ids.add(r.recipeId), "unique identity");
            assertNotNull(c.provenance(r.recipeId));
            assertTrue(c.provenance(r.recipeId).revision() > 0);
        }
    }

    @Test
    void sourceShapedVectorsKeepLowerBoundAndVerifiedMeansPerCategory() {
        MysticForgeCatalog c = MysticForgeCatalogLoader.bundled();
        Recipe gossamer = byOutputPage(c, "Gossamer Scrap#recipe1");
        assertEquals(19745, gossamer.outputItemId);
        assertEquals(10, gossamer.outputCount, "SMW lower bound is the guaranteed minimum, not a mean");
        assertEquals(new BigDecimal("18.92"), gossamer.expectedYield.mean());
        assertEquals(40, gossamer.expectedYield.max());
        assertEquals(java.util.Map.of(19748, 250, 19745, 1, 24277, 5, 20796, 5), counts(gossamer));
        Recipe wool = byOutputPage(c, "Wool Scrap#recipe1");
        assertEquals(40, wool.outputCount);
        assertEquals(new BigDecimal("86.34"), wool.expectedYield.mean());
        assertEquals(java.util.Map.of(19718, 250, 19739, 1, 24273, 5, 20796, 1), counts(wool));
        Recipe scepter = byOutputPage(c, "Abyssal Scepter#recipe1");
        assertEquals(-3105801, scepter.recipeId);
        assertNull(scepter.expectedYield, "a template without an upper quantity is a fixed output");
    }

    @Test
    void everyBundledYieldIsAVerifiedCategoryMeanAndNoRandomRowIsFixed() {
        MysticForgeCatalog c = MysticForgeCatalogLoader.bundled();
        Set<String> means = Set.of("86.34", "18.92", "18.51", "6.91");
        long withYield = c.recipes().stream().filter(Recipe::hasExpectedYield).count();
        assertEquals(c.coverage().expectedYield(), withYield);
        assertTrue(withYield > 50);
        for (Recipe r : c.recipes()) {
            if (r.expectedYield == null) continue;
            assertTrue(means.contains(r.expectedYield.mean().toPlainString()), r.expectedYield.toString());
            assertEquals(r.outputCount, r.expectedYield.min());
            assertTrue(r.selfSeedUnits() == 1, "promotion consumes exactly one self seed");
            assertNotEquals(0, r.expectedYield.mean().compareTo(
                    BigDecimal.valueOf(r.expectedYield.min() + r.expectedYield.max()).divide(BigDecimal.valueOf(2))),
                    "never the min/max midpoint");
        }
    }

    @Test
    void displayOnlyAverageAndUnverifiedRandomRowsAreAbsent() {
        MysticForgeCatalog c = MysticForgeCatalogLoader.bundled();
        assertTrue(c.recipes().stream().noneMatch(r -> c.provenance(r.recipeId).page().startsWith("Mystic Clover#")));
    }

    @Test
    void validExpectedMetadataAndProvenanceAreExposed() throws Exception {
        MysticForgeCatalog c = of(rec("101", ",\"yield\":{\"category\":\"x\",\"mean\":\"2.5\",\"min\":1,\"max\":4}"));
        Recipe r = c.recipes().getFirst();
        assertEquals(new BigDecimal("2.5"), r.expectedYield.mean());
        assertEquals("A#recipe1", c.provenance(r.recipeId).page());
        assertEquals(5L, c.provenance(r.recipeId).revision());
    }

    @Test
    void strictScalarTypesAreNeverCoerced() throws Exception {
        MysticForgeCatalog c = of(String.join(",",
                rec("1.5", ""), rec("\"2\"", ""),
                "{\"forgeId\":3,\"outputItemId\":10,\"outputCount\":\"1\",\"page\":\"A\",\"revision\":5,\"ingredients\":[[1,2]]}",
                "{\"forgeId\":4,\"outputItemId\":10,\"outputCount\":1,\"page\":\"A\",\"revision\":5,\"ingredients\":[[1,2.5]]}",
                "{\"forgeId\":5,\"outputItemId\":10,\"outputCount\":1,\"page\":\"A\",\"revision\":5,\"ingredients\":[[1,2],[1,3]]}",
                "{\"forgeId\":6,\"outputItemId\":10,\"outputCount\":1,\"revision\":5,\"ingredients\":[[1,2]]}",
                rec("7", ",\"yield\":{\"category\":\"x\",\"mean\":2.5,\"min\":1,\"max\":4}"),
                rec("8", ",\"yield\":{\"category\":\"x\",\"mean\":\"9\",\"min\":1,\"max\":4}"),
                rec("9", ",\"yield\":{\"category\":\"x\",\"mean\":\"2\",\"min\":2,\"max\":4}")));
        assertTrue(c.recipes().isEmpty(), "every malformed recipe is rejected, none priced");
        assertEquals(9, c.coverage().excludedByReason().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void duplicateIdentitiesDropEveryCopy() throws Exception {
        MysticForgeCatalog c = of(rec("11", "") + "," + rec("11", "") + "," + rec("12", ""));
        assertEquals(1, c.recipes().size());
        assertEquals(-12, c.recipes().getFirst().recipeId);
        assertEquals(2, c.coverage().excludedByReason().get("rejected at load: duplicate forgeId"));
    }

    @Test
    void unusableResourcesAreObservableAndEmpty() throws Exception {
        assertFalse(MysticForgeCatalogLoader.load("/mystic-forge/does-not-exist.json").isUsable());
        assertTrue(MysticForgeCatalogLoader.load("/mystic-forge/does-not-exist.json").recipes().isEmpty());
        MysticForgeCatalog wrong = MysticForgeCatalogLoader.fromJson(new ObjectMapper().readTree("{\"recipes\":[]}"));
        assertFalse(wrong.isUsable());
        assertNotEquals(MysticForgeCatalogLoader.bundled().fingerprint(), wrong.fingerprint());
    }

    @Test
    void fingerprintCoversYieldAndProvenance() throws Exception {
        String y = ",\"yield\":{\"category\":\"x\",\"mean\":\"2.5\",\"min\":1,\"max\":4}";
        MysticForgeCatalog a = of(rec("1", y));
        assertNotEquals(a.fingerprint(), of(rec("1", y.replace("2.5", "2.6"))).fingerprint());
        assertNotEquals(a.fingerprint(), of(rec("1", y.replace("\"max\":4", "\"max\":5"))).fingerprint());
        assertNotEquals(a.fingerprint(), of(rec("1", y).replace("\"revision\":5", "\"revision\":6")).fingerprint());
        assertEquals(a.fingerprint(), of(rec("1", y)).fingerprint());
    }

    private static java.util.Map<Integer, Integer> counts(Recipe r) {
        java.util.Map<Integer, Integer> m = new java.util.HashMap<>();
        r.ingredients.forEach(i -> m.merge(i.itemId, i.count, Integer::sum));
        return m;
    }
}
