package sync;

import api.BatchUtils;
import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import parser.ItemParser;

import java.nio.file.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class IconSync {

    private IconSync() {}

    private record IconUpdate(int itemId, String iconPath) {}
    private static final int ICON_FLUSH_BATCH = SyncConstants.HTTP_IDS_BATCH;

    public static void syncItemIconUrls() throws Exception {

        List<Integer> ids = new ArrayList<>();

        try (Connection con = Db.openConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("""
             SELECT item_id
             FROM items
             WHERE icon_url IS NULL
             ORDER BY item_id
         """)) {

            while (rs.next())
                ids.add(rs.getInt(1));
        }

        System.out.println("Items missing icon_url: " + ids.size());

        if (ids.isEmpty())
            return;

        String updateSql = """
        UPDATE items
        SET icon_url = ?
        WHERE item_id = ?
        """;

        int done = 0;

        try (Connection con = Db.openConnection();
             PreparedStatement psUpdate = con.prepareStatement(updateSql)) {

            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {

                String idsParam = BatchUtils.idsParam(batch);

                String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;

                JsonNode root = Gw2ApiClient.getPublicArray(url);

                List<IconUpdate> updates = new ArrayList<>();

                for (JsonNode item : root) {

                    var row = ItemParser.parse(item);

                    if (row == null)
                        continue;

                    String iconUrl = row.iconUrl();

                    if (iconUrl == null || iconUrl.isBlank())
                        continue;

                    updates.add(new IconUpdate(row.itemId(), iconUrl));
                }

                con.setAutoCommit(false);

                try {

                    psUpdate.clearBatch();

                    for (IconUpdate u : updates) {

                        psUpdate.setString(1, u.iconPath());
                        psUpdate.setInt(2, u.itemId());

                        psUpdate.addBatch();
                    }

                    psUpdate.executeBatch();

                    con.commit();

                } catch (Exception ex) {

                    con.rollback();
                    throw ex;
                }

                done += batch.size();

                System.out.println(
                        "Updated icon_url progress "
                                + Math.min(done, ids.size())
                                + " / "
                                + ids.size());
            }
        }

        System.out.println("✅ icon_url synced");
    }

    public static void syncItemIconsToDisk(Path iconBaseDir) throws Exception {

        Path itemsDir = iconBaseDir.resolve("items");
        Files.createDirectories(itemsDir);

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

        try (Connection con = Db.openConnection();
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

        int downloaded = 0;
        int skippedNoUrl = 0;
        int skippedAlreadyExists = 0;
        int failed = 0;

        for (IconJob job : jobs) {

            int itemId = job.itemId();
            String iconUrl = job.iconUrl();

            if (iconUrl == null || iconUrl.isBlank()) {
                skippedNoUrl++;
                continue;
            }

            Path target = itemsDir.resolve(itemId + ".png");

            if (Files.exists(target) && Files.size(target) > 0) {
                updates.add(new IconUpdate(itemId, target.toString()));
                skippedAlreadyExists++;
                continue;
            }

            try {

                var res = Gw2ApiClient.getBytesResponse(iconUrl);

                if (res.statusCode() != 200 || res.body() == null || res.body().length == 0) {

                    System.out.println("Icon download failed item "
                                               + itemId + " HTTP " + res.statusCode());

                    failed++;
                    continue;
                }

                Path tmp = target.resolveSibling(itemId + ".png.tmp");

                Files.write(tmp, res.body(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);

                Files.move(tmp, target,
                           StandardCopyOption.REPLACE_EXISTING,
                           StandardCopyOption.ATOMIC_MOVE);

                updates.add(new IconUpdate(itemId, target.toString()));
                downloaded++;

            }
            catch (Exception ex) {

                System.out.println("Icon download exception item "
                                           + itemId + ": " + ex.getMessage());

                failed++;
            }

            if (updates.size() >= ICON_FLUSH_BATCH) {

                flushIconPathUpdates(updateSql, updates);
                updates.clear();

                System.out.println("Downloaded icons: "
                                           + downloaded + " | failed=" + failed);
            }
        }

        flushIconPathUpdates(updateSql, updates);

        System.out.println("✅ Item icons synced. Downloaded="
                                   + downloaded
                                   + " skippedNoUrl=" + skippedNoUrl
                                   + " skippedAlreadyExists=" + skippedAlreadyExists
                                   + " failed=" + failed);
    }

    private static void flushIconPathUpdates(String updateSql, List<IconUpdate> updates) throws SQLException {

        if (updates == null || updates.isEmpty()) return;

        try (Connection con = Db.openConnection();
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