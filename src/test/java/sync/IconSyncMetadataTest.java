package sync;

import application.icons.ItemIconUrls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import parser.ItemParser;
import repo.EnvConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for the icon-<em>metadata</em>
 * refresh of {@code IconSync} (STORY-API-009 and STORY-SYNC-004, TARGET_ARCHITECTURE.md §12.1).
 *
 * <p>What §12.1 requires and the original null-only backfill did not establish:
 * <ul>
 *   <li><b>Coverage</b> - every item an item-bearing view can reference is selected: bank slots,
 *       material storage, character inventories, recipe outputs and recipe ingredients, nontradeable
 *       items included, plus anything still missing metadata entirely.</li>
 *   <li><b>Reachability</b> (STORY-SYNC-004) - a referenced id with <em>no</em> {@code items} row is
 *       discovered too, and the row is created from the canonical response.</li>
 *   <li><b>Refresh</b> - a URL that <em>changed</em> is written, not only a null one, while valid
 *       retained metadata and unrelated account data survive.</li>
 * </ul>
 *
 * <p>Runs against a disposable, uniquely-named schema on the local Postgres server, following the same
 * pattern as {@link AccountSyncTest}. Upstream metadata is supplied by a controlled fixture through
 * {@code IconSync.ItemMetadataSource}, so no GW2 API call is made and no image is downloaded - which
 * is also what proves the repair is independent of the desktop icon download.
 */
class IconSyncMetadataTest {

    private static final String ECTO_URL =
            "https://render.guildwars2.com/file/4BB6C2D3B6A7C4C8E1F9A0B1C2D3E4F5A6B7C8D9/219316.png";
    private static final String ECTO_URL_NEW_VERSION =
            "https://render.guildwars2.com/file/1111111111111111111111111111111111111111/219316.png";
    private static final String LOG_URL =
            "https://render.guildwars2.com/file/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/63133.png";

    /** Well-formed, but not on the render host - the canonical policy rejects it. */
    private static final String REJECTED_URL = "https://example.invalid/file/AABB/1.png";

    private final String schema = "test_iconsync_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        con = DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));

        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);

        try (Statement st = con.createStatement()) {
            st.execute("""
                CREATE TABLE items (
                    item_id      INTEGER PRIMARY KEY,
                    name         TEXT,
                    type         TEXT,
                    rarity       TEXT,
                    vendor_value INTEGER,
                    icon_path    TEXT,
                    icon_url     TEXT,
                    fetched_at   TIMESTAMPTZ
                )
                """);
            st.execute("CREATE TABLE account_bank (slot INTEGER PRIMARY KEY, item_id INTEGER, count INTEGER)");
            st.execute("CREATE TABLE account_materials (item_id INTEGER PRIMARY KEY, category INTEGER, count INTEGER)");
            st.execute("CREATE TABLE character_items (character_name TEXT, item_id INTEGER, count INTEGER)");
            st.execute("CREATE TABLE recipes (recipe_id INTEGER PRIMARY KEY, output_item_id INTEGER)");
            st.execute("CREATE TABLE recipe_ingredients (recipe_id INTEGER, item_id INTEGER, count INTEGER)");
            st.execute("CREATE TABLE tp_prices (item_id INTEGER PRIMARY KEY, buy_unit_price INTEGER, sell_unit_price INTEGER)");
        }
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        if (con == null) return;
        try (Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    // ---------------------------------------------------------------- selection

    /**
     * Every item id below already has metadata, so nothing here is selected by the old null-only rule:
     * whatever the selection returns, it returns because the item is <em>referenced</em>.
     */
    private void seedReferencedItemsThatAllHaveMetadata() throws Exception {
        try (Statement st = con.createStatement()) {
            for (int itemId : List.of(10, 20, 30, 40, 50, 60)) {
                st.execute("INSERT INTO items (item_id, name, icon_url) VALUES ("
                                   + itemId + ", 'Item " + itemId + "', '" + ECTO_URL + "')");
            }

            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 10, 5)");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (1, NULL, NULL)");
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (20, 5, 250)");
            st.execute("INSERT INTO character_items (character_name, item_id, count) VALUES ('Hero', 30, 1)");
            st.execute("INSERT INTO recipes (recipe_id, output_item_id) VALUES (900, 40)");
            st.execute("INSERT INTO recipes (recipe_id, output_item_id) VALUES (901, NULL)");
            st.execute("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (900, 50, 3)");

            // 60 is referenced by nothing and already has metadata: the one item that must stay out.
        }
    }

    @Test
    void coversEveryItemAnItemBearingViewCanReferenceEvenWhenItAlreadyHasMetadata() throws Exception {
        seedReferencedItemsThatAllHaveMetadata();

        List<Integer> selected = IconSync.referencedOrUnknownItemIds(con);

        assertEquals(List.of(10, 20, 30, 40, 50), selected,
                     "bank slot, material stack, character inventory, recipe output and recipe "
                             + "ingredient are all covered; an unreferenced item with metadata is not");
    }

    /**
     * STORY-SYNC-004's reason for existing: account synchronization stores the id, nothing creates the
     * metadata row, and the old selection read {@code FROM items} - so the id could never be reached.
     */
    @Test
    void aReferencedIdWithNoItemsRowAtAllIsDiscovered() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 10, 5)");
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (20, 5, 250)");
            st.execute("INSERT INTO character_items (character_name, item_id, count) VALUES ('Hero', 30, 1)");
            st.execute("INSERT INTO recipes (recipe_id, output_item_id) VALUES (900, 40)");
            st.execute("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (900, 50, 3)");
        }

        assertEquals(List.of(10, 20, 30, 40, 50), IconSync.referencedOrUnknownItemIds(con),
                     "every reference family is reachable without an items row");
    }

    @Test
    void aReferencedNontradeableItemIsCoveredLikeAnyOther() throws Exception {
        seedReferencedItemsThatAllHaveMetadata();

        // Only item 10 is on the Trading Post. Item 20 - a referenced material stack - is not, which is
        // what "nontradeable" means here: the selection joins no tradeability source, so it stays in.
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price) VALUES (10, 100, 120)");
        }

        assertTrue(IconSync.referencedOrUnknownItemIds(con).contains(20),
                   "a referenced item with no Trading Post price must still be refreshed");
    }

    @Test
    void anItemWithNoMetadataAtAllIsStillCoveredEvenWhenNothingReferencesIt() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO items (item_id, name, icon_url) VALUES (70, 'Never seen', NULL)");
            st.execute("INSERT INTO items (item_id, name, icon_url) VALUES (71, 'Known', '" + ECTO_URL + "')");
        }

        assertEquals(List.of(70), IconSync.referencedOrUnknownItemIds(con),
                     "the original backfill behaviour is retained on top of the referenced set");
    }

    @Test
    void anEmptyItemTableSelectsNothing() throws Exception {
        assertEquals(List.of(), IconSync.referencedOrUnknownItemIds(con));
    }

    // ---------------------------------------------------------------- repair

    /**
     * The whole product intent of Request-009 in one case: an occupied bank slot whose item has no
     * metadata row obtains real icon metadata, and the established application-relative URL with it.
     */
    @Test
    void aBankSlotWhoseItemHasNoMetadataRowObtainsRealIconMetadata() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 10, 5)");
        }

        FixtureMetadataSource upstream = new FixtureMetadataSource();
        upstream.knows(itemJson(10, "Glob of Ectoplasm", "CraftingMaterial", "Rare", 16, ECTO_URL));

        IconSync.MetadataRepair repair = IconSync.repairItemIconMetadata(con, upstream);

        assertEquals(1, repair.selected());
        assertEquals(1, repair.rowsWritten());
        assertEquals(1, repair.rowsCreated(), "the missing metadata row was created, not skipped");

        assertEquals("Glob of Ectoplasm", stringColumn("name", 10));
        assertEquals("CraftingMaterial", stringColumn("type", 10));
        assertEquals("Rare", stringColumn("rarity", 10));
        assertEquals(ECTO_URL, iconUrlOf(10));
        assertEquals(16, intColumn("vendor_value", 10), "the full canonical row is retained");
        assertNotNull(stringColumn("fetched_at", 10), "the created row records when it was resolved");

        assertNotNull(ItemIconUrls.iconUrlFor(10, iconUrlOf(10)),
                      "the repaired source yields the established application-relative icon URL");
    }

    @Test
    void anExistingRowWithoutAnIconSourceIsFilledAndItsOtherMetadataIsPreserved() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO items (item_id, name, type, rarity, vendor_value, icon_path, icon_url)"
                               + " VALUES (10, 'Stored Name', 'StoredType', 'Exotic', 42,"
                               + " 'C:\\icons\\items\\10.png', NULL)");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 10, 5)");
        }

        FixtureMetadataSource upstream = new FixtureMetadataSource();
        upstream.knows(itemJson(10, "Upstream Name", "UpstreamType", "Junk", 1, ECTO_URL));

        IconSync.MetadataRepair repair = IconSync.repairItemIconMetadata(con, upstream);

        assertEquals(1, repair.rowsWritten());
        assertEquals(0, repair.rowsCreated(), "an existing row is updated, never recreated");

        assertEquals(ECTO_URL, iconUrlOf(10), "the icon source is the one value this repair fills");
        assertEquals("Stored Name", stringColumn("name", 10), "valid retained metadata survives");
        assertEquals("StoredType", stringColumn("type", 10));
        assertEquals("Exotic", stringColumn("rarity", 10));
        assertEquals(42, intColumn("vendor_value", 10));
        assertEquals("C:\\icons\\items\\10.png", iconPathOf(10),
                     "the metadata repair is not allowed to touch the desktop path column");
    }

    /**
     * The other half of "complete metadata coverage": a row can exist and still be missing the
     * canonical fields an item view shows. Upstream returns them, so the repair fills exactly the
     * missing ones and leaves every populated value alone.
     */
    @Test
    void anExistingRowMissingCanonicalFieldsHasThemFilledWithoutOverwritingWhatItHolds() throws Exception {
        try (Statement st = con.createStatement()) {
            // 10: nothing but the icon source. 20: a stored name that must survive the repair.
            st.execute("INSERT INTO items (item_id, name, type, rarity, vendor_value, icon_path, icon_url)"
                               + " VALUES (10, NULL, NULL, NULL, NULL,"
                               + " 'C:\\icons\\items\\10.png', '" + ECTO_URL + "')");
            st.execute("INSERT INTO items (item_id, name, type, rarity, vendor_value, icon_url)"
                               + " VALUES (20, 'Stored Log', NULL, NULL, NULL, '" + LOG_URL + "')");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 10, 5)");
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (20, 5, 250)");
        }

        FixtureMetadataSource upstream = new FixtureMetadataSource();
        upstream.knows(itemJson(10, "Glob of Ectoplasm", "CraftingMaterial", "Rare", 16, ECTO_URL));
        upstream.knows(itemJson(20, "Upstream Log", "CraftingMaterial", "Basic", 1, LOG_URL));

        IconSync.MetadataRepair repair = IconSync.repairItemIconMetadata(con, upstream);

        assertEquals(2, repair.rowsWritten(), "both half-populated rows were completed");
        assertEquals(0, repair.rowsCreated(), "an existing row is completed, never recreated");

        assertEquals("Glob of Ectoplasm", stringColumn("name", 10), "the missing name was filled");
        assertEquals("CraftingMaterial", stringColumn("type", 10));
        assertEquals("Rare", stringColumn("rarity", 10));
        assertEquals(16, intColumn("vendor_value", 10));
        assertEquals(ECTO_URL, iconUrlOf(10), "the icon source it already held is unchanged");
        assertEquals("C:\\icons\\items\\10.png", iconPathOf(10),
                     "completing the metadata is not allowed to touch the desktop path column");

        assertEquals("Stored Log", stringColumn("name", 20), "a populated value is never overwritten");
        assertEquals("CraftingMaterial", stringColumn("type", 20), "its missing fields are filled anyway");
        assertEquals("Basic", stringColumn("rarity", 20));
        assertEquals(1, intColumn("vendor_value", 20));

        assertEquals(0, IconSync.repairItemIconMetadata(con, upstream).rowsWritten(),
                     "once complete, repeating the repair costs no row updates");
    }

    @Test
    void aChangedIconSourceIsWrittenAndRepeatingTheRepairCostsNoRowUpdates() throws Exception {
        // Both rows are canonically complete, so the only thing a repair can find to do is the URL.
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO items (item_id, name, type, rarity, vendor_value, icon_url)"
                               + " VALUES (10, 'Ecto', 'CraftingMaterial', 'Rare', 16, '" + ECTO_URL + "')");
            st.execute("INSERT INTO items (item_id, name, type, rarity, vendor_value, icon_url)"
                               + " VALUES (20, 'Log', 'CraftingMaterial', 'Basic', 1, '" + LOG_URL + "')");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 10, 5)");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (1, 20, 7)");
        }

        FixtureMetadataSource upstream = new FixtureMetadataSource();
        upstream.knows(itemJson(10, "Ecto", "CraftingMaterial", "Rare", 16, ECTO_URL_NEW_VERSION));
        upstream.knows(itemJson(20, "Log", "CraftingMaterial", "Basic", 1, LOG_URL));

        assertEquals(1, IconSync.repairItemIconMetadata(con, upstream).rowsWritten(),
                     "only the item whose upstream source changed is written");
        assertEquals(ECTO_URL_NEW_VERSION, iconUrlOf(10),
                     "an item whose upstream URL changed is updated, not skipped because it was non-null");
        assertEquals(LOG_URL, iconUrlOf(20));

        assertEquals(0, IconSync.repairItemIconMetadata(con, upstream).rowsWritten(),
                     "repeating the repair is safe and costs no row updates");
        assertEquals(ECTO_URL_NEW_VERSION, iconUrlOf(10));
        assertEquals(LOG_URL, iconUrlOf(20));
    }

    /**
     * §12.1: an unacceptable or absent source is not an error - it yields no icon URL and the existing
     * neutral fallback stands. What must not happen is a fabricated row or a blanked stored source.
     */
    @Test
    void missingAndRejectedUpstreamResponsesAreClassifiedAndNothingIsFabricated() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 10, 1)");  // unknown upstream
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (1, 20, 1)");  // no icon upstream
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (2, 30, 1)");  // rejected source
            st.execute("INSERT INTO items (item_id, name, icon_url) VALUES (40, 'Kept', '" + LOG_URL + "')");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (3, 40, 1)");  // stored, none upstream
        }

        FixtureMetadataSource upstream = new FixtureMetadataSource();
        upstream.knows(itemJson(20, "No Icon", "Trophy", "Basic", 0, null));
        upstream.knows(itemJson(30, "Foreign Icon", "Trophy", "Basic", 0, REJECTED_URL));
        upstream.knows(itemJson(40, "Kept", "Trophy", "Basic", 0, null));

        IconSync.MetadataRepair repair = IconSync.repairItemIconMetadata(con, upstream);

        assertEquals(1, repair.notReturnedUpstream(), "item 10 was not returned at all");
        assertEquals(2, repair.withoutIconSource(), "items 20 and 40 carry no icon upstream");
        assertEquals(1, repair.rejectedIconSource(), "item 30's source is not on the render host");

        assertFalse(itemRowExists(10), "an unresolved id gets no fabricated row");
        assertNull(iconUrlOf(20), "no icon upstream means no icon source, not an invented one");
        assertEquals(REJECTED_URL, iconUrlOf(30), "what upstream reported is retained verbatim");
        assertNull(ItemIconUrls.iconUrlFor(30, iconUrlOf(30)),
                   "a rejected source stays a genuine fallback case at the presentation boundary");
        assertEquals(LOG_URL, iconUrlOf(40),
                     "a stored valid source is never blanked because upstream reported none");

        assertTrue(IconSync.referencedOrUnknownItemIds(con).contains(10),
                   "the unresolved id remains eligible for a later explicit repair");
    }

    /**
     * A failed batch must not abort the run or poison the ids it covered: the remaining batches are
     * still repaired, and the skipped ids are picked up by the next explicit repair.
     */
    @Test
    void aFailedBatchIsSkippedAndRepairedByTheNextRun() throws Exception {
        FixtureMetadataSource upstream = new FixtureMetadataSource();

        try (Statement st = con.createStatement()) {
            for (int itemId = 1000; itemId <= 1249; itemId++) {
                st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES ("
                                   + (itemId - 1000) + ", " + itemId + ", 3)");
                upstream.knows(itemJson(itemId, "Item " + itemId, "Trophy", "Basic", 0, ECTO_URL));
            }
        }

        // 250 selected ids are two batches of 200 + 50; the second one fails.
        upstream.failsForBatchContaining(1249);

        IconSync.MetadataRepair failed = IconSync.repairItemIconMetadata(con, upstream);

        assertEquals(250, failed.selected());
        assertEquals(2, upstream.requestedBatches().size(), "the run continued past the failed batch");
        assertEquals(1, failed.batchesFailed());
        assertEquals(50, failed.idsLeftUnrequested());
        assertEquals(200, failed.rowsCreated(), "the batches that answered were still repaired");
        assertTrue(itemRowExists(1000));
        assertFalse(itemRowExists(1249), "nothing was fabricated for the batch that failed");

        upstream.failsForNothing();

        IconSync.MetadataRepair repaired = IconSync.repairItemIconMetadata(con, upstream);

        assertEquals(0, repaired.batchesFailed());
        assertEquals(50, repaired.rowsCreated(), "the previously skipped ids were repaired");
        assertEquals(ECTO_URL, iconUrlOf(1249));
    }

    @Test
    void theRepairPreservesAccountQuantitiesAndUnrelatedMetadata() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 10, 250)");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (1, NULL, NULL)");
            st.execute("INSERT INTO account_materials (item_id, category, count) VALUES (20, 5, 1337)");
            st.execute("INSERT INTO items (item_id, name, type, rarity, vendor_value, icon_url)"
                               + " VALUES (60, 'Unreferenced', 'Trophy', 'Exotic', 99, '" + LOG_URL + "')");
        }

        FixtureMetadataSource upstream = new FixtureMetadataSource();
        upstream.knows(itemJson(10, "Ecto", "CraftingMaterial", "Rare", 16, ECTO_URL));
        upstream.knows(itemJson(20, "Log", "CraftingMaterial", "Basic", 1, LOG_URL));

        IconSync.repairItemIconMetadata(con, upstream);

        assertEquals(250, countInBankSlot(0));
        assertEquals(1337, materialCountOf(20));
        assertEquals(2, bankSlotCount(), "the empty slot is still an empty slot");

        assertEquals("Unreferenced", stringColumn("name", 60), "unrelated metadata is untouched");
        assertEquals(LOG_URL, iconUrlOf(60));
        assertEquals(99, intColumn("vendor_value", 60));
    }

    /**
     * §12.1: "keep metadata acquisition usable without binary downloads or desktop setup". The repair
     * neither needs nor produces a local icon directory.
     */
    @Test
    void theRepairDownloadsNoImageBytes() throws Exception {
        Path iconCacheDir = Files.createTempDirectory("icon-metadata-repair-");

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 10, 5)");
        }

        FixtureMetadataSource upstream = new FixtureMetadataSource();
        upstream.knows(itemJson(10, "Ecto", "CraftingMaterial", "Rare", 16, ECTO_URL));

        IconSync.repairItemIconMetadata(con, upstream);

        try (var entries = Files.list(iconCacheDir)) {
            assertEquals(List.of(), entries.toList(),
                         "no image was downloaded: the repair needs no icon directory at all");
        }

        Files.delete(iconCacheDir);
    }

    @Test
    void anEmptyBatchIsAcceptedAndWritesNothing() throws Exception {
        assertEquals(new IconSync.MetadataWrite(0, 0), IconSync.applyItemMetadata(con, List.of()));
    }

    /**
     * Replaces the pre-STORY-SYNC-004 expectation that metadata for an item absent from {@code items}
     * "changes nothing": creating that row is exactly what this story adds. Every other row stays put.
     */
    @Test
    void metadataForAnItemAbsentFromItemsCreatesItsRowAndLeavesTheOthersAlone() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO items (item_id, name, icon_url) VALUES (10, 'Ecto', '" + ECTO_URL + "')");
        }

        IconSync.MetadataWrite written = IconSync.applyItemMetadata(
                con, List.of(new ItemParser.ItemRow(999, "Late arrival", "Trophy", "Basic", 5, LOG_URL)));

        assertEquals(new IconSync.MetadataWrite(1, 1), written);
        assertEquals(LOG_URL, iconUrlOf(999));
        assertEquals(ECTO_URL, iconUrlOf(10));
    }

    // ---------------------------------------------------------------- fixture

    /** A controlled stand-in for the GW2 items endpoint: it answers from a fixed catalogue. */
    private static final class FixtureMetadataSource implements IconSync.ItemMetadataSource {

        private final Map<Integer, ObjectNode> catalogue = new LinkedHashMap<>();
        private final Set<Integer> failsForIds = new HashSet<>();
        private final List<List<Integer>> requestedBatches = new ArrayList<>();

        void knows(ObjectNode item) {
            catalogue.put(item.get("id").asInt(), item);
        }

        void failsForBatchContaining(int itemId) {
            failsForIds.add(itemId);
        }

        void failsForNothing() {
            failsForIds.clear();
        }

        List<List<Integer>> requestedBatches() {
            return requestedBatches;
        }

        @Override
        public JsonNode fetchByIds(List<Integer> itemIds) throws Exception {

            requestedBatches.add(List.copyOf(itemIds));

            for (Integer id : itemIds) {
                if (failsForIds.contains(id)) throw new IOException("upstream unavailable");
            }

            ArrayNode answer = JsonNodeFactory.instance.arrayNode();

            // Upstream omits ids it does not know rather than returning a placeholder for them.
            for (Integer id : itemIds) {
                ObjectNode item = catalogue.get(id);
                if (item != null) answer.add(item);
            }

            return answer;
        }
    }

    private static ObjectNode itemJson(
            int itemId, String name, String type, String rarity, Integer vendorValue, String icon) {

        ObjectNode item = JsonNodeFactory.instance.objectNode();

        item.put("id", itemId);
        item.put("name", name);
        item.put("type", type);
        item.put("rarity", rarity);

        if (vendorValue != null) item.put("vendor_value", vendorValue);
        if (icon != null) item.put("icon", icon);

        return item;
    }

    // ---------------------------------------------------------------- reads

    private String iconUrlOf(int itemId) throws Exception {
        return stringColumn("icon_url", itemId);
    }

    private String iconPathOf(int itemId) throws Exception {
        return stringColumn("icon_path", itemId);
    }

    private String stringColumn(String column, int itemId) throws Exception {
        return singleStringColumn("SELECT " + column + " FROM items WHERE item_id = " + itemId);
    }

    private int intColumn(String column, int itemId) throws Exception {
        return Integer.parseInt(stringColumn(column, itemId));
    }

    private boolean itemRowExists(int itemId) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM items WHERE item_id = " + itemId)) {
            return rs.next();
        }
    }

    private int countInBankSlot(int slot) throws Exception {
        return Integer.parseInt(singleStringColumn("SELECT count FROM account_bank WHERE slot = " + slot));
    }

    private int bankSlotCount() throws Exception {
        return Integer.parseInt(singleStringColumn("SELECT count(*) FROM account_bank"));
    }

    private int materialCountOf(int itemId) throws Exception {
        return Integer.parseInt(
                singleStringColumn("SELECT count FROM account_materials WHERE item_id = " + itemId));
    }

    private String singleStringColumn(String sql) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            assertTrue(rs.next(), "expected exactly one row for: " + sql);
            return rs.getString(1);
        }
    }
}
