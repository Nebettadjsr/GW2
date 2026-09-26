package sync;

import api.BatchUtils;
import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import infra.icons.FilesystemIconStore;
import infra.icons.HttpIconImageFetcher;
import infra.icons.IconAcquisition;
import parser.ItemParser;
import repo.Db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IconSync {

    private IconSync() {}

    /** Argument that reports what {@link #syncItemIconUrls()} would cover without calling anything. */
    private static final String DRY_RUN = "--dry-run";

    /**
     * Standalone entry point for the icon-<em>metadata</em> refresh (TARGET_ARCHITECTURE.md §12.1:
     * "keep metadata acquisition usable without binary downloads or desktop setup; no new browser sync
     * control is required").
     *
     * <p>It runs {@link #syncItemIconUrls()} only - no image is downloaded and no local icon directory
     * is needed - so a backend-only machine can repair missing metadata without the JavaFX first-setup
     * flow and without a browser control. {@code --dry-run} reports how many items the refresh would
     * cover and calls neither the GW2 API nor any write.
     *
     * <p>Invocation is documented in {@code docs/CURRENT_ARCHITECTURE.md} §5.14.
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && DRY_RUN.equals(args[0])) {
            System.out.println("Items the icon-metadata refresh would cover: "
                                       + referencedOrUnknownItemIds().size());
            return;
        }

        syncItemIconUrls();
    }

    private record IconUpdate(int itemId, String iconPath) {}
    private static final int ICON_FLUSH_BATCH = SyncConstants.HTTP_IDS_BATCH;

    /** Only a differing value is written, so an unchanged refresh costs no row updates. */
    private static final String ICON_URL_UPDATE_SQL = """
        UPDATE items
        SET icon_url = ?
        WHERE item_id = ?
          AND (icon_url IS NULL OR icon_url <> ?)
        """;

    /**
     * Explicit icon-metadata refresh (TARGET_ARCHITECTURE.md §12.1): brings {@code items.icon_url} up
     * to date for every item the application actually shows, and updates a URL that changed rather
     * than only filling a null one.
     *
     * <p>Covered by the selection below: account bank slots, material storage, character inventories,
     * recipe outputs and recipe ingredients - nontradeable items included, since nothing here filters
     * by tradeability - plus any item that still has no metadata at all, which keeps the original
     * backfill behavior. The previous null-only selection established neither coverage nor refresh.
     *
     * <p>This is metadata only. No image is downloaded and no local directory is needed, so it is
     * usable on a machine that never runs the desktop icon download; the web image endpoint repairs
     * its own binaries on demand and never triggers this call.
     */
    public static void syncItemIconUrls() throws Exception {

        List<Integer> ids = referencedOrUnknownItemIds();

        System.out.println("Items to refresh icon_url for: " + ids.size());

        if (ids.isEmpty())
            return;

        int done = 0;
        int changed = 0;

        try (Connection con = Db.open()) {

            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {

                String idsParam = BatchUtils.idsParam(batch);

                String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;

                JsonNode root = Gw2ApiClient.getPublicArray(url);

                Map<Integer, String> refreshedUrls = new LinkedHashMap<>();

                for (JsonNode item : root) {

                    var row = ItemParser.parse(item);

                    if (row == null)
                        continue;

                    String iconUrl = row.iconUrl();

                    if (iconUrl == null || iconUrl.isBlank())
                        continue;

                    refreshedUrls.put(row.itemId(), iconUrl);
                }

                changed += applyIconUrlUpdates(con, refreshedUrls);

                done += batch.size();

                System.out.println(
                        "Refreshed icon_url progress "
                                + Math.min(done, ids.size())
                                + " / "
                                + ids.size());
            }
        }

        System.out.println("✅ icon_url synced, " + changed + " row(s) changed");
    }

    /**
     * Writes one batch of refreshed metadata, in its own transaction.
     *
     * <p>A row is written only when the stored URL actually differs, so a URL that <em>changed</em> is
     * updated rather than skipped and an unchanged refresh costs no row updates. {@code items.icon_path}
     * is not touched: this is metadata only, and it needs no local icon directory.
     *
     * @param refreshedUrls item id to the URL upstream currently reports
     * @return how many rows changed
     */
    static int applyIconUrlUpdates(Connection con, Map<Integer, String> refreshedUrls) throws SQLException {

        if (refreshedUrls.isEmpty()) return 0;

        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);

        try (PreparedStatement psUpdate = con.prepareStatement(ICON_URL_UPDATE_SQL)) {

            for (Map.Entry<Integer, String> refreshed : refreshedUrls.entrySet()) {

                psUpdate.setString(1, refreshed.getValue());
                psUpdate.setInt(2, refreshed.getKey());
                psUpdate.setString(3, refreshed.getValue());

                psUpdate.addBatch();
            }

            int changed = 0;
            for (int affected : psUpdate.executeBatch()) {
                if (affected > 0) changed++;
            }

            con.commit();
            return changed;

        } catch (SQLException notWritten) {

            con.rollback();
            throw notWritten;

        } finally {
            con.setAutoCommit(autoCommit);
        }
    }

    private static List<Integer> referencedOrUnknownItemIds() throws SQLException {

        try (Connection con = Db.open()) {
            return referencedOrUnknownItemIds(con);
        }
    }

    /**
     * The items whose icon metadata is worth holding: everything an item-bearing view can reference,
     * plus anything still missing metadata entirely.
     *
     * <p>Nothing here filters by tradeability, so a referenced nontradeable item is covered like any
     * other.
     */
    static List<Integer> referencedOrUnknownItemIds(Connection con) throws SQLException {

        String sql = """
        SELECT i.item_id
        FROM items i
        WHERE i.icon_url IS NULL
           OR i.item_id IN (
                SELECT item_id FROM account_bank WHERE item_id IS NOT NULL
                UNION SELECT item_id FROM account_materials WHERE item_id IS NOT NULL
                UNION SELECT item_id FROM character_items
                UNION SELECT output_item_id FROM recipes WHERE output_item_id IS NOT NULL
                UNION SELECT item_id FROM recipe_ingredients
              )
        ORDER BY i.item_id
        """;

        List<Integer> ids = new ArrayList<>();

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            while (rs.next())
                ids.add(rs.getInt(1));
        }

        return ids;
    }

    /**
     * Explicit desktop icon download (TARGET_ARCHITECTURE.md §12.1): gives every item with retained
     * metadata and no local file a shared, source-keyed image in
     * {@code <iconBaseDir>/assets-v1} and points {@code items.icon_path} at it.
     *
     * <p>It writes through the same cache adapter, key and publication protocol the web image endpoint
     * uses, so the two never maintain independent download stores: whatever either side published is a
     * hit for the other, and a re-run downloads nothing that is already committed. The path is recorded
     * only after publication, and only for the source the item currently retains.
     *
     * <p>Legacy {@code items/{itemId}.png} files are left in place and stay usable by JavaFX; see
     * {@link DesktopIconAdoption} for why their bytes are not adopted without evidence. No mass
     * migration or redownload happens here.
     */
    public static void syncItemIconsToDisk(Path iconBaseDir) throws Exception {

        FilesystemIconStore store = new FilesystemIconStore(iconBaseDir);
        DesktopIconAdoption adoption = new DesktopIconAdoption(
                iconBaseDir, store, new IconAcquisition(store, new HttpIconImageFetcher()));

        String selectSql = """
    SELECT item_id, icon_url
    FROM items
    WHERE icon_url IS NOT NULL
      AND (icon_path IS NULL OR icon_path = '')
    ORDER BY item_id
    """;

        String updateSql = """
    UPDATE items
    SET icon_path = ?
    WHERE item_id = ?
    """;

        record IconJob(int itemId, String iconUrl) {}
        List<IconJob> jobs = new ArrayList<>();

        try (Connection con = Db.open();
             PreparedStatement psSelect = con.prepareStatement(selectSql);
             ResultSet rs = psSelect.executeQuery()) {

            while (rs.next()) {

                int itemId = rs.getInt("item_id");
                String iconUrl = rs.getString("icon_url");

                if (iconUrl == null || iconUrl.isBlank()) continue;

                jobs.add(new IconJob(itemId, iconUrl));
            }
        }

        System.out.println("Icon jobs to process: " + jobs.size());
        if (jobs.isEmpty()) return;

        List<IconUpdate> updates = new ArrayList<>();
        Map<DesktopIconAdoption.Outcome, Integer> outcomes = new EnumMap<>(DesktopIconAdoption.Outcome.class);

        for (IconJob job : jobs) {

            DesktopIconAdoption.Adoption result = adoption.adopt(job.itemId(), job.iconUrl());
            outcomes.merge(result.outcome(), 1, Integer::sum);

            if (result.file() != null) {
                updates.add(new IconUpdate(job.itemId(), result.file().toString()));
            }

            if (updates.size() >= ICON_FLUSH_BATCH) {

                flushIconPathUpdates(updateSql, updates);
                updates.clear();

                System.out.println("Icon progress: " + outcomes);
            }
        }

        flushIconPathUpdates(updateSql, updates);

        System.out.println("✅ Item icons synced into " + store.assetsRoot() + " " + outcomes);
    }

    private static void flushIconPathUpdates(String updateSql, List<IconUpdate> updates) throws SQLException {

        if (updates == null || updates.isEmpty()) return;

        try (Connection con = Db.open();
             PreparedStatement psUpdate = con.prepareStatement(updateSql)) {

            con.setAutoCommit(false);

            for (IconUpdate u : updates) {

                psUpdate.setString(1, u.iconPath());
                psUpdate.setInt(2, u.itemId());

                psUpdate.addBatch();
            }

            psUpdate.executeBatch();
            con.commit();
        }
    }
}
