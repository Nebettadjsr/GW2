package sync;

import api.BatchUtils;
import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import infra.icons.FilesystemIconStore;
import infra.icons.HttpIconImageFetcher;
import infra.icons.IconAcquisition;
import infra.icons.IconSourcePolicy;
import parser.ItemParser;
import repo.Db;
import util.DbBind;

import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * cover, and how many of those have no {@code items} row at all, and calls neither the GW2 API nor
     * any write.
     *
     * <p>Invocation is documented in {@code docs/CURRENT_ARCHITECTURE.md} §5.14.
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && DRY_RUN.equals(args[0])) {
            reportCoverage();
            return;
        }

        syncItemIconUrls();
    }

    /** {@code --dry-run}: what the repair would cover, using the selection itself and no GW2 call. */
    private static void reportCoverage() throws SQLException {

        try (Connection con = Db.open()) {

            List<Integer> ids = referencedOrUnknownItemIds(con);

            int withoutRow = 0;
            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {
                withoutRow += batch.size() - existingItemIds(con, batch).size();
            }

            System.out.println("Items the icon-metadata refresh would cover: " + ids.size());
            System.out.println("  of which have no items row at all: " + withoutRow);
        }
    }

    private record IconUpdate(int itemId, String iconPath) {}
    private static final int ICON_FLUSH_BATCH = SyncConstants.HTTP_IDS_BATCH;

    /**
     * Upsert, because a referenced item may have no {@code items} row at all (STORY-SYNC-004): account
     * synchronization stores the id, and nothing ever creates the metadata row an icon needs.
     *
     * <p>A row that is created carries the canonical metadata upstream reports. A row that already
     * exists keeps every value it holds - each {@code COALESCE} prefers the stored side, so a populated
     * field is never overwritten - and has its <em>missing</em> canonical fields filled from the
     * response, so an id whose row was created without metadata is completed rather than left half
     * empty. {@code icon_url} is updated when upstream supplied one and it differs from the stored one,
     * and coalesces the other way round so a response without an icon never blanks a stored source. The
     * {@code WHERE} keeps the write to rows that actually gain something, so an unchanged refresh costs
     * no row updates.
     */
    private static final String ITEM_METADATA_UPSERT_SQL = """
        INSERT INTO items (item_id, name, type, rarity, vendor_value, icon_url, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (item_id) DO UPDATE SET
          name         = COALESCE(items.name, EXCLUDED.name),
          type         = COALESCE(items.type, EXCLUDED.type),
          rarity       = COALESCE(items.rarity, EXCLUDED.rarity),
          vendor_value = COALESCE(items.vendor_value, EXCLUDED.vendor_value),
          icon_url     = COALESCE(EXCLUDED.icon_url, items.icon_url)
        WHERE (EXCLUDED.icon_url IS NOT NULL AND items.icon_url IS DISTINCT FROM EXCLUDED.icon_url)
           OR (items.name IS NULL AND EXCLUDED.name IS NOT NULL)
           OR (items.type IS NULL AND EXCLUDED.type IS NOT NULL)
           OR (items.rarity IS NULL AND EXCLUDED.rarity IS NOT NULL)
           OR (items.vendor_value IS NULL AND EXCLUDED.vendor_value IS NOT NULL)
        """;

    /** One batch of GW2 item metadata, so a controlled fixture can stand in for the live endpoint. */
    @FunctionalInterface
    interface ItemMetadataSource {

        /** @return the {@code /v2/items} array for these ids; upstream omits ids it does not know */
        JsonNode fetchByIds(List<Integer> itemIds) throws Exception;
    }

    /**
     * What one repair run covered and what it could not resolve, so the remaining fallbacks can be
     * classified instead of merely counted (STORY-SYNC-004 acceptance criterion 6).
     *
     * @param selected            ids the selection asked for
     * @param rowsWritten         rows created or updated
     * @param rowsCreated         of those, rows that had no {@code items} row before
     * @param notReturnedUpstream selected ids upstream did not return at all
     * @param withoutIconSource   returned items that carry no icon reference upstream
     * @param rejectedIconSource  returned items whose icon reference the canonical policy rejects
     * @param batchesFailed       batches whose metadata request failed, left for a later repair
     * @param idsLeftUnrequested  ids in those failed batches
     */
    record MetadataRepair(
            int selected,
            int rowsWritten,
            int rowsCreated,
            int notReturnedUpstream,
            int withoutIconSource,
            int rejectedIconSource,
            int batchesFailed,
            int idsLeftUnrequested) {}

    /**
     * Explicit icon-metadata refresh (TARGET_ARCHITECTURE.md §12.1): brings item icon metadata up to
     * date for every item the application actually shows, and updates a URL that changed rather than
     * only filling a null one.
     *
     * <p>Covered by the selection below: account bank slots, material storage, character inventories,
     * recipe outputs and recipe ingredients - nontradeable items included, since nothing here filters
     * by tradeability - plus any item that still has no metadata at all. Since STORY-SYNC-004 the
     * selection no longer starts from the {@code items} table, so a referenced id whose metadata row
     * was never created is discovered and the row is created from the canonical response.
     *
     * <p>This is metadata only. No image is downloaded and no local directory is needed, so it is
     * usable on a machine that never runs the desktop icon download; the web image endpoint repairs
     * its own binaries on demand and never triggers this call.
     */
    public static void syncItemIconUrls() throws Exception {

        try (Connection con = Db.open()) {

            MetadataRepair repair = repairItemIconMetadata(con, IconSync::fetchItemMetadata);

            System.out.println("✅ icon metadata synced: " + repair);
        }
    }

    /**
     * Resolves the selected items' metadata one batch at a time and persists it.
     *
     * <p>A batch whose metadata request fails is reported and skipped, not fatal: nothing is fabricated
     * for its ids, nothing retained is destroyed, and because the selection is recomputed from the
     * references on every run, those ids are picked up again by the next explicit repair.
     */
    static MetadataRepair repairItemIconMetadata(Connection con, ItemMetadataSource source)
            throws Exception {

        List<Integer> ids = referencedOrUnknownItemIds(con);

        System.out.println("Items to refresh icon metadata for: " + ids.size());

        int done = 0;
        int written = 0;
        int created = 0;
        int notReturned = 0;
        int withoutSource = 0;
        int rejectedSource = 0;
        int batchesFailed = 0;
        int idsLeftUnrequested = 0;

        for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {

            JsonNode root;

            try {
                root = source.fetchByIds(batch);

            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;

            } catch (Exception unavailable) {

                batchesFailed++;
                idsLeftUnrequested += batch.size();
                done += batch.size();

                System.out.println("Icon metadata unavailable for " + batch.size()
                                           + " item(s), left for a later repair: " + unavailable);
                continue;
            }

            List<ItemParser.ItemRow> rows = new ArrayList<>();
            Set<Integer> returned = new HashSet<>();

            for (JsonNode item : root) {

                ItemParser.ItemRow row = ItemParser.parse(item);

                if (row == null || !returned.add(row.itemId()))
                    continue;

                String iconSource = row.iconUrl();

                if (iconSource == null || iconSource.isBlank())
                    withoutSource++;
                else if (IconSourcePolicy.accept(iconSource).isEmpty())
                    rejectedSource++;

                rows.add(row);
            }

            for (Integer id : batch) {
                if (!returned.contains(id)) notReturned++;
            }

            MetadataWrite batchWrite = applyItemMetadata(con, rows);
            written += batchWrite.rowsWritten();
            created += batchWrite.rowsCreated();

            done += batch.size();

            System.out.println("Refreshed icon metadata progress " + done + " / " + ids.size());
        }

        return new MetadataRepair(ids.size(), written, created, notReturned,
                                  withoutSource, rejectedSource, batchesFailed, idsLeftUnrequested);
    }

    /** The live metadata adapter: the same public GW2 items endpoint every other sync uses. */
    private static JsonNode fetchItemMetadata(List<Integer> itemIds) throws Exception {

        String idsParam = BatchUtils.idsParam(itemIds);

        if (idsParam.isBlank())
            return JsonNodeFactory.instance.arrayNode();

        return Gw2ApiClient.getPublicArray("https://api.guildwars2.com/v2/items?ids=" + idsParam);
    }

    /** @param rowsWritten rows created or updated, of which {@code rowsCreated} did not exist before */
    record MetadataWrite(int rowsWritten, int rowsCreated) {}

    /**
     * Writes one batch of resolved metadata, in its own transaction.
     *
     * <p>Creates the {@code items} row when a referenced id has none, and otherwise fills whichever
     * canonical fields the stored row is missing and updates {@code icon_url} when it differs - so a
     * URL that <em>changed</em> is updated rather than skipped, a half-populated row is completed, a
     * populated field is left alone, an unchanged refresh costs no row updates and repeating the repair
     * is safe. {@code items.icon_path} is never touched: this is metadata only, and it needs no local
     * icon directory.
     *
     * @param rows the canonical metadata upstream returned for this batch
     */
    static MetadataWrite applyItemMetadata(Connection con, List<ItemParser.ItemRow> rows)
            throws SQLException {

        if (rows.isEmpty()) return new MetadataWrite(0, 0);

        Set<Integer> existedBefore = existingItemIds(con, itemIdsOf(rows));

        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);

        try (PreparedStatement psUpsert = con.prepareStatement(ITEM_METADATA_UPSERT_SQL)) {

            for (ItemParser.ItemRow row : rows) {

                psUpsert.setInt(1, row.itemId());
                DbBind.setStringOrNull(psUpsert, 2, row.name());
                DbBind.setStringOrNull(psUpsert, 3, row.type());
                DbBind.setStringOrNull(psUpsert, 4, row.rarity());

                psUpsert.setInt(5, row.vendorValue() == null ? 0 : row.vendorValue());
                DbBind.setStringOrNull(psUpsert, 6, row.iconUrl());

                psUpsert.addBatch();
            }

            int[] affected = psUpsert.executeBatch();

            int written = 0;
            int created = 0;

            for (int i = 0; i < affected.length; i++) {

                if (affected[i] <= 0) continue;

                written++;
                if (!existedBefore.contains(rows.get(i).itemId())) created++;
            }

            con.commit();
            return new MetadataWrite(written, created);

        } catch (SQLException notWritten) {

            con.rollback();
            throw notWritten;

        } finally {
            con.setAutoCommit(autoCommit);
        }
    }

    private static List<Integer> itemIdsOf(List<ItemParser.ItemRow> rows) {

        List<Integer> ids = new ArrayList<>(rows.size());

        for (ItemParser.ItemRow row : rows)
            ids.add(row.itemId());

        return ids;
    }

    /** Which of these ids already have an {@code items} row, so a create can be told from an update. */
    private static Set<Integer> existingItemIds(Connection con, Collection<Integer> itemIds)
            throws SQLException {

        Set<Integer> existing = new LinkedHashSet<>();

        if (itemIds.isEmpty()) return existing;

        try (PreparedStatement ps =
                     con.prepareStatement("SELECT item_id FROM items WHERE item_id = ANY(?)")) {

            Array ids = con.createArrayOf("int", itemIds.toArray());
            ps.setArray(1, ids);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    existing.add(rs.getInt(1));
            }
        }

        return existing;
    }

    /**
     * The items whose icon metadata is worth holding: everything an item-bearing view can reference,
     * plus anything still missing metadata entirely.
     *
     * <p>The referenced families are read directly, <em>not</em> through the {@code items} table, so an
     * id an account or recipe row refers to is discovered even when no metadata row was ever created
     * for it (STORY-SYNC-004). Nothing here filters by tradeability, so a referenced nontradeable item
     * is covered like any other.
     */
    static List<Integer> referencedOrUnknownItemIds(Connection con) throws SQLException {

        String sql = """
        SELECT item_id FROM (
            SELECT item_id FROM items WHERE icon_url IS NULL
            UNION SELECT item_id FROM account_bank WHERE item_id IS NOT NULL
            UNION SELECT item_id FROM account_materials WHERE item_id IS NOT NULL
            UNION SELECT item_id FROM character_items WHERE item_id IS NOT NULL
            UNION SELECT output_item_id FROM recipes WHERE output_item_id IS NOT NULL
            UNION SELECT item_id FROM recipe_ingredients WHERE item_id IS NOT NULL
        ) referenced
        ORDER BY item_id
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
