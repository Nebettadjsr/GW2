package sync;

import api.BatchUtils;
import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import domain.icons.IconSourcePolicy;
import parser.ItemParser;
import repo.Db;
import util.DbBind;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class IconSync {

    private IconSync() {}

    /** Argument that reports what {@link #syncItemIconUrls()} would cover without calling anything. */
    private static final String DRY_RUN = "--dry-run";

    /**
     * Standalone entry point for the icon-<em>metadata</em> refresh.
     *
     * <p>It runs {@link #syncItemIconUrls()} only - no image is downloaded and no local icon directory
     * is needed - so a backend-only machine can repair missing metadata without a browser control.
     * {@code --dry-run} reports how many items the refresh would
     * cover, and how many of those have no {@code items} row at all, and calls neither the GW2 API nor
     * any write.
     *
     * <p>Run it with no argument for the refresh or with {@code --dry-run} for the coverage report.
     */
    public static void main(String[] args) throws Exception {
        // A retained entry point passes the same startup schema gate as the backend before it opens a connection.
        infra.db.SchemaInitializer.initializeRuntimeDatabase();

        if (args.length > 0 && DRY_RUN.equals(args[0])) {
            reportCoverage();
            return;
        }

        syncItemIconUrls();
    }

    /** {@code --dry-run}: what the repair would cover, using the selection itself and no GW2 call. */
    private static void reportCoverage() throws SQLException {

        try (Connection con = Db.open()) {

            List<Integer> ids = metadataRefreshSelection(con);

            int withoutRow = 0;
            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {
                withoutRow += batch.size() - existingItemIds(con, batch).size();
            }

            System.out.println("Items the icon-metadata refresh would cover: " + ids.size());
            System.out.println("  of which have no items row at all: " + withoutRow);
        }
    }

    /**
     * Upsert, because a referenced item may have no cached {@code items} row at all: account
     * synchronization stores item ids, and the metadata row an icon needs is created by this write
     * (the pre-publication {@link #repairMissingMetadata} or an explicit refresh), so a reference may
     * lack cached metadata until then.
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
          name         = COALESCE(NULLIF(btrim(items.name), ''), NULLIF(btrim(EXCLUDED.name), ''), items.name),
          type         = COALESCE(NULLIF(btrim(items.type), ''), NULLIF(btrim(EXCLUDED.type), ''), items.type),
          rarity       = COALESCE(NULLIF(btrim(items.rarity), ''), NULLIF(btrim(EXCLUDED.rarity), ''), items.rarity),
          vendor_value = COALESCE(items.vendor_value, EXCLUDED.vendor_value),
          icon_url     = COALESCE(EXCLUDED.icon_url, items.icon_url)
        WHERE (EXCLUDED.icon_url IS NOT NULL AND items.icon_url IS DISTINCT FROM EXCLUDED.icon_url)
           OR (btrim(coalesce(items.name, '')) = '' AND btrim(coalesce(EXCLUDED.name, '')) <> '')
           OR (btrim(coalesce(items.type, '')) = '' AND btrim(coalesce(EXCLUDED.type, '')) <> '')
           OR (btrim(coalesce(items.rarity, '')) = '' AND btrim(coalesce(EXCLUDED.rarity, '')) <> '')
           OR (items.vendor_value IS NULL AND EXCLUDED.vendor_value IS NOT NULL)
        """;

    /** One batch of GW2 item metadata, so a controlled fixture can stand in for the live endpoint. */
    @FunctionalInterface
    interface ItemMetadataSource {

        /** @return the {@code /v2/items} array for these ids; upstream omits ids it does not know */
        JsonNode fetchByIds(List<Integer> itemIds) throws Exception;
    }

    @FunctionalInterface
    interface ItemMetadataRepair {
        void repair(Collection<Integer> ids) throws Exception;
    }

    /**
     * What one repair run covered and what it could not resolve, so the remaining fallbacks can be
     * classified instead of merely counted.
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
     * Explicit icon-metadata refresh: brings item icon metadata up to
     * date for every item the application actually shows, and updates a URL that changed rather than
     * only filling a null one.
     *
     * <p>Covered by the selection below: account bank slots, material storage, character inventories,
     * recipe outputs and recipe ingredients - nontradeable items included, since nothing here filters
     * by tradeability - plus any item that still has no metadata at all. The
     * selection no longer starts from the {@code items} table, so a referenced id whose metadata row
     * was never created is discovered and the row is created from the canonical response.
     *
     * <p>On the versioned database the three account-owned families are left out, because this entry
     * point carries no account scope; see {@link #metadataRefreshSelection}.
     *
     * <p>This is metadata only. No image is downloaded and no local directory is needed; the web image
     * endpoint repairs its own binaries on demand and never triggers this call.
     */
    public static void syncItemIconUrls() throws Exception {

        try (Connection con = Db.open()) {

            MetadataRepair repair = repairItemIconMetadata(con, IconSync::fetchItemMetadata);

            System.out.println("✅ icon metadata synced: " + repair);
        }
    }

    /** Fill metadata for explicitly requested item IDs using the same parser and upsert as the bulk refresh. */
    public static void syncItemMetadataByIds(Set<Integer> itemIds) throws Exception {
        for (List<Integer> batch : BatchUtils.chunk(new ArrayList<>(itemIds), SyncConstants.HTTP_IDS_BATCH)) {
            List<ItemParser.ItemRow> rows = parseRequested(fetchItemMetadata(batch), batch);
            try (Connection con = Db.open()) {
                applyItemMetadata(con, rows);
            }
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

        List<Integer> ids = metadataRefreshSelection(con);

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

    /**
     * Completes metadata for item ids an account refresh is about to publish (and ids it already
     * stores): ids with no {@code items} row, no accepted {@code icon_url}, or a blank name, type or
     * rarity are fetched from the canonical items endpoint and written through the ordinary metadata
     * upsert, so a first or later account sync needs no separate global refresh. On the managed schema
     * a row also counts as incomplete while {@code no_mystic_forge} is null. Complete ids cost no
     * upstream call.
     *
     * <p>This runs <em>before</em> the scoped account publication (AccountRefreshGateway, character
     * refresh), on the caller's own connection and never inside the account publication transaction.
     * Each upstream batch is written in its own short metadata transaction, so a failed batch loses
     * only that batch and never touches last-good account state.
     *
     * <p>Best effort and bounded: an unavailable or malformed batch is reported and left for the next
     * sync or the on-demand read repair; it does not fail the refresh. An interrupt is not swallowed: the
     * thread's interrupt flag is restored and the {@link InterruptedException} propagates.
     *
     * @return the ids whose metadata request failed
     */
    static Set<Integer> repairMissingMetadata(Connection con, Collection<Integer> itemIds,
                                              ItemMetadataSource source) throws Exception {

        Set<Integer> failed = new LinkedHashSet<>();
        Set<Integer> wanted = new java.util.TreeSet<>();
        for (Integer id : itemIds)
            if (id != null) wanted.add(id);
        if (wanted.isEmpty()) return failed;

        wanted.removeAll(completeIds(con, wanted));

        for (List<Integer> batch : BatchUtils.chunk(new ArrayList<>(wanted), SyncConstants.HTTP_IDS_BATCH)) {
            try {
                JsonNode root = source.fetchByIds(batch);
                if (root == null || !root.isArray()) throw new IllegalArgumentException("Item metadata must be an array");
                applyItemMetadata(con, parseRequested(root, batch));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception unavailable) {
                failed.addAll(batch);
                System.out.println("Icon metadata unavailable for " + batch.size()
                                           + " account item(s), left for a later sync: " + unavailable);
            }
        }
        return failed;
    }

    private static Set<Integer> completeIds(Connection con, Collection<Integer> wanted) throws SQLException {
        return repo.ItemRepository.completeMetadataIds(con, wanted);
    }

    /** The positive ids of {@code itemIds} whose stored metadata row is missing or incomplete (see {@link repo.ItemRepository#incompleteIds}). */
    public static Set<Integer> incompleteIds(Connection con, Collection<Integer> itemIds) throws SQLException {
        return repo.ItemRepository.incompleteIds(con, itemIds);
    }

    /**
     * Connection-free completion of item metadata for {@code itemIds}: a short read finds the ids whose stored
     * row is incomplete, the batched upstream fetch runs with no database connection held, and each batch is
     * written in its own short transaction. A failed batch is reported and stays incomplete, so the next call
     * retries it; an interrupt propagates with the flag restored.
     *
     * @return the ids whose metadata request failed
     */
    public static Set<Integer> completeMissingMetadata(Collection<Integer> itemIds) throws Exception {
        return completeMissingMetadata(itemIds, IconSync::fetchItemMetadata);
    }

    static Set<Integer> completeMissingMetadata(Collection<Integer> itemIds, ItemMetadataSource source)
            throws Exception {
        Set<Integer> failed = new LinkedHashSet<>();
        Set<Integer> wanted = new java.util.TreeSet<>();
        for (Integer id : itemIds) if (id != null && id > 0) wanted.add(id);
        if (wanted.isEmpty()) return failed;
        try (Connection con = Db.open()) {
            wanted.removeAll(completeIds(con, wanted));
        }
        for (List<Integer> batch : BatchUtils.chunk(new ArrayList<>(wanted), SyncConstants.HTTP_IDS_BATCH)) {
            try {
                JsonNode root = source.fetchByIds(batch);
                if (root == null || !root.isArray()) throw new IllegalArgumentException("Item metadata must be an array");
                List<ItemParser.ItemRow> rows = parseRequested(root, batch);
                try (Connection con = Db.open()) {
                    applyItemMetadata(con, rows);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception unavailable) {
                failed.addAll(batch);
                System.out.println("Item metadata unavailable for " + batch.size()
                                           + " Forge item(s), left for a later refresh: " + unavailable);
            }
        }
        return failed;
    }

    /** Live-endpoint form of {@link #repairMissingMetadata(Connection, Collection, ItemMetadataSource)}. */
    public static void repairMissingMetadata(Connection con, Collection<Integer> itemIds) throws Exception {
        repairMissingMetadata(con, itemIds, IconSync::fetchItemMetadata);
    }

    /** Parses one upstream batch, keeping each requested id once and dropping unrequested or unparsable entries. */
    private static List<ItemParser.ItemRow> parseRequested(JsonNode root, Collection<Integer> batch) {
        Set<Integer> requested = new HashSet<>(batch);
        Set<Integer> returned = new HashSet<>();
        List<ItemParser.ItemRow> rows = new ArrayList<>();
        for (JsonNode item : root) {
            ItemParser.ItemRow row = ItemParser.parse(item);
            if (row != null && requested.contains(row.itemId()) && returned.add(row.itemId())) rows.add(row);
        }
        return rows;
    }

    /** The live metadata adapter: the same public GW2 items endpoint every other sync uses. */
    static JsonNode fetchItemMetadata(List<Integer> itemIds) throws Exception {

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

            ItemCraftingMetadata.store(con, rows);
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
     * What one unscoped icon-metadata refresh covers: the global selection only.
     *
     * <p>The shared item, recipe and material-catalog references need no account, so global icon
     * metadata stays available with no verified credential. The account-owned families are not read
     * here, since this entry point carries no account scope and must not read across accounts; their
     * items get metadata through {@link #repairMissingMetadata}, which an account refresh calls with
     * the ids it is about to publish (including installed bag containers).
     */
    private static List<Integer> metadataRefreshSelection(Connection con) throws SQLException {
        return globalReferencedOrUnknownItemIds(con);
    }

    /**
     * The global half of the selection: shared recipe and material-catalog references, plus any item
     * that still has no metadata at all.
     *
     * <p>Reads no account table, so it requires no {@code model.AccountScope} and is refused by
     * nothing - this is the selection that keeps the icon-metadata refresh usable on a migrated,
     * empty-account database. It
     * reads the referenced families directly rather than through {@code items}, so a referenced id
     * with no metadata row is still discovered, and it filters by no tradeability source.
     */
    /** Test seam: the bundled Forge/Clover/vendor reference ids that join the global selection. */
    static volatile java.util.function.Supplier<Set<Integer>> forgeReferenceIds =
            parser.MysticForgeCatalogLoader::referencedItemIds;

    static List<Integer> globalReferencedOrUnknownItemIds(Connection con) throws SQLException {
        // Bundled Forge/Clover/vendor references join the same global selection (no new refresh path).
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
        SELECT item_id FROM (
            SELECT item_id FROM items WHERE icon_url IS NULL
            UNION SELECT item_id FROM material_category_items
            UNION SELECT output_item_id FROM recipes WHERE output_item_id IS NOT NULL
            UNION SELECT item_id FROM recipe_ingredients WHERE item_id IS NOT NULL
            UNION SELECT unnest(?::int[])
        ) referenced
        ORDER BY item_id
        """)) {
            ps.setArray(1, con.createArrayOf("int", forgeReferenceIds.get().toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        }
        return ids;
    }

    /** Runs one parameterless item-id selection, in the order the statement itself established. */
    private static List<Integer> selectItemIds(Connection con, String sql) throws SQLException {

        List<Integer> ids = new ArrayList<>();

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            while (rs.next())
                ids.add(rs.getInt(1));
        }

        return ids;
    }

}
