package sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.EnvConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for the icon-<em>metadata</em>
 * refresh of {@code IconSync.syncItemIconUrls} (STORY-API-009, TARGET_ARCHITECTURE.md §12.1).
 *
 * <p>Two things §12.1 requires and the previous null-only backfill did not establish:
 * <ul>
 *   <li><b>Coverage</b> - every item an item-bearing view can reference is selected: bank slots,
 *       material storage, character inventories, recipe outputs and recipe ingredients, nontradeable
 *       items included, plus anything still missing metadata entirely.</li>
 *   <li><b>Refresh</b> - a URL that <em>changed</em> is written, not only a null one.</li>
 * </ul>
 *
 * <p>Runs against a disposable, uniquely-named schema on the local Postgres server, following the same
 * pattern as {@link AccountSyncTest}. The two connection-taking methods under test are the selection
 * and the write, so no GW2 API call is made and no image is downloaded - which is also what proves the
 * refresh is independent of the desktop icon download.
 */
class IconSyncMetadataTest {

    private static final String ECTO_URL =
            "https://render.guildwars2.com/file/4BB6C2D3B6A7C4C8E1F9A0B1C2D3E4F5A6B7C8D9/219316.png";
    private static final String ECTO_URL_NEW_VERSION =
            "https://render.guildwars2.com/file/1111111111111111111111111111111111111111/219316.png";
    private static final String LOG_URL =
            "https://render.guildwars2.com/file/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/63133.png";

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

    @Test
    void aChangedUrlIsWrittenAndAnUnchangedOneCostsNoRowUpdate() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO items (item_id, name, icon_url) VALUES (10, 'Ecto', '" + ECTO_URL + "')");
            st.execute("INSERT INTO items (item_id, name, icon_url) VALUES (20, 'Log', '" + LOG_URL + "')");
            st.execute("INSERT INTO items (item_id, name, icon_url) VALUES (30, 'Unknown', NULL)");
        }

        Map<Integer, String> refreshed = new LinkedHashMap<>();
        refreshed.put(10, ECTO_URL_NEW_VERSION);   // changed upstream
        refreshed.put(20, LOG_URL);                // unchanged
        refreshed.put(30, LOG_URL);                // filled for the first time

        assertEquals(2, IconSync.applyIconUrlUpdates(con, refreshed),
                     "only the changed and the newly filled row count as changed");

        assertEquals(ECTO_URL_NEW_VERSION, iconUrlOf(10),
                     "an item whose upstream URL changed is updated, not skipped because it was non-null");
        assertEquals(LOG_URL, iconUrlOf(20));
        assertEquals(LOG_URL, iconUrlOf(30));
    }

    @Test
    void refreshingAnItemThatIsGoneChangesNothing() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO items (item_id, name, icon_url) VALUES (10, 'Ecto', '" + ECTO_URL + "')");
        }

        assertEquals(0, IconSync.applyIconUrlUpdates(con, Map.of(999, LOG_URL)));
        assertEquals(ECTO_URL, iconUrlOf(10));
    }

    @Test
    void anEmptyBatchIsAcceptedAndWritesNothing() throws Exception {
        assertEquals(0, IconSync.applyIconUrlUpdates(con, Map.of()));
    }

    /**
     * §12.1: "keep metadata acquisition usable without binary downloads or desktop setup". The refresh
     * neither needs nor produces a local icon directory, and it leaves {@code items.icon_path} - the
     * desktop column - exactly as it found it.
     */
    @Test
    void theRefreshDownloadsNoImageAndLeavesTheDesktopPathAlone() throws Exception {
        Path iconCacheDir = Files.createTempDirectory("icon-metadata-refresh-");

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO items (item_id, name, icon_path, icon_url) VALUES "
                               + "(10, 'Ecto', 'C:\\icons\\items\\10.png', '" + ECTO_URL + "')");
            st.execute("INSERT INTO items (item_id, name, icon_path, icon_url) VALUES "
                               + "(20, 'Log', NULL, NULL)");
        }

        IconSync.applyIconUrlUpdates(con, Map.of(10, ECTO_URL_NEW_VERSION, 20, LOG_URL));

        assertEquals("C:\\icons\\items\\10.png", iconPathOf(10),
                     "the metadata refresh is not allowed to touch the desktop path column");
        assertNull(iconPathOf(20), "and it does not invent one either");

        try (var entries = Files.list(iconCacheDir)) {
            assertEquals(List.of(), entries.toList(),
                         "no image was downloaded: the refresh needs no icon directory at all");
        }

        Files.delete(iconCacheDir);
    }

    private String iconUrlOf(int itemId) throws Exception {
        return singleStringColumn("SELECT icon_url FROM items WHERE item_id = " + itemId);
    }

    private String iconPathOf(int itemId) throws Exception {
        return singleStringColumn("SELECT icon_path FROM items WHERE item_id = " + itemId);
    }

    private String singleStringColumn(String sql) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            assertTrue(rs.next(), "expected exactly one row for: " + sql);
            return rs.getString(1);
        }
    }
}
