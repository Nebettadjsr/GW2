package sync;

import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import model.BankSlot;
import model.MaterialStack;
import parser.BankParser;
import parser.MaterialParser;
import parser.RecipeIdParser;
import util.DbBind;

import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class AccountSync {
    private AccountSync() {}

    public static void syncAccountBank() throws IOException, InterruptedException, SQLException {
        String url = "https://api.guildwars2.com/v2/account/bank";
        JsonNode root = Gw2ApiClient.getAuth(url);

        if (root == null || !root.isArray()) {
            throw new RuntimeException("Unexpected JSON (bank): not an array");
        }

        List<BankSlot> rows = new ArrayList<>(root.size());
        for (int slot = 0; slot < root.size(); slot++) {
            JsonNode entry = root.get(slot);
            rows.add(BankParser.parseSlot(slot, entry));
        }

        String upsertSql = """
            INSERT INTO account_bank
              (slot, item_id, count, binding, bound_to, charges, stats_id, stats_attrs, fetched_at)
            VALUES
              (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (slot) DO UPDATE SET
              item_id     = EXCLUDED.item_id,
              count       = EXCLUDED.count,
              binding     = EXCLUDED.binding,
              bound_to    = EXCLUDED.bound_to,
              charges     = EXCLUDED.charges,
              stats_id    = EXCLUDED.stats_id,
              stats_attrs = EXCLUDED.stats_attrs::jsonb,
              fetched_at  = EXCLUDED.fetched_at
            """;

        String deleteStaleSql = "DELETE FROM account_bank WHERE fetched_at < ?";

        try (Connection con = Db.openConnection()) {
            con.setAutoCommit(false);
            try {
                Timestamp runTs;
                try (PreparedStatement psNow = con.prepareStatement("SELECT now()");
                     ResultSet rs = psNow.executeQuery()) {
                    if (!rs.next()) throw new SQLException("SELECT now() returned no row");
                    runTs = rs.getTimestamp(1);
                }

                try (PreparedStatement ps = con.prepareStatement(upsertSql)) {
                    for (BankSlot row : rows) {
                        ps.setInt(1, row.slot());
                        DbBind.setIntOrNull(ps, 2, row.itemId());
                        DbBind.setIntOrNull(ps, 3, row.count());
                        DbBind.setStringOrNull(ps, 4, row.binding());
                        DbBind.setStringOrNull(ps, 5, row.boundTo());
                        DbBind.setIntOrNull(ps, 6, row.charges());
                        DbBind.setIntOrNull(ps, 7, row.statsId());
                        DbBind.setStringOrNull(ps, 8, row.statsAttrsJson());
                        ps.setTimestamp(9, runTs);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                try (PreparedStatement psDel = con.prepareStatement(deleteStaleSql)) {
                    psDel.setTimestamp(1, runTs);
                    psDel.executeUpdate();
                }

                con.commit();
            } catch (Exception ex) {
                con.rollback();
                throw ex;
            }
        }
    }

    public static void syncAccountMaterials() throws Exception {
        String url = "https://api.guildwars2.com/v2/account/materials";
        JsonNode root = Gw2ApiClient.getAuth(url);

        if (root == null || !root.isArray()) {
            throw new RuntimeException("Unexpected JSON (materials): not an array");
        }

        List<MaterialStack> rows = new ArrayList<>(root.size());
        for (JsonNode entry : root) {
            MaterialStack row = MaterialParser.parse(entry);
            if (row != null) rows.add(row);
        }

        String upsertSql = """
            INSERT INTO account_materials (item_id, category, count, binding, fetched_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (item_id) DO UPDATE SET
              category   = EXCLUDED.category,
              count      = EXCLUDED.count,
              binding    = EXCLUDED.binding,
              fetched_at = EXCLUDED.fetched_at
            """;

        String deleteStaleSql = "DELETE FROM account_materials WHERE fetched_at < ?";

        try (Connection con = Db.openConnection()) {
            con.setAutoCommit(false);
            try {
                Timestamp runTs;
                try (PreparedStatement psNow = con.prepareStatement("SELECT now()");
                     ResultSet rs = psNow.executeQuery()) {
                    if (!rs.next()) throw new SQLException("SELECT now() returned no row");
                    runTs = rs.getTimestamp(1);
                }

                try (PreparedStatement ps = con.prepareStatement(upsertSql)) {
                    for (MaterialStack row : rows) {
                        ps.setInt(1, row.itemId());
                        ps.setInt(2, row.category());
                        ps.setInt(3, row.count());
                        DbBind.setStringOrNull(ps, 4, row.binding());
                        ps.setTimestamp(5, runTs);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                try (PreparedStatement psDel = con.prepareStatement(deleteStaleSql)) {
                    psDel.setTimestamp(1, runTs);
                    psDel.executeUpdate();
                }

                con.commit();
            } catch (Exception ex) {
                con.rollback();
                throw ex;
            }
        }
    }

    public static void syncAccountRecipes() throws Exception {
        String url = "https://api.guildwars2.com/v2/account/recipes";

        JsonNode root = Gw2ApiClient.getAuth(url);
        if (root == null || !root.isArray()) {
            throw new RuntimeException("Unexpected JSON (account/recipes): not an array");
        }

        List<Integer> ids = new ArrayList<>(root.size());
        for (JsonNode idNode : root) {
            Integer id = RecipeIdParser.parse(idNode);
            if (id != null && id > 0) ids.add(id);
        }

        String upsertSql = """
            INSERT INTO account_recipes (recipe_id, fetched_at)
            VALUES (?, ?)
            ON CONFLICT (recipe_id) DO UPDATE SET fetched_at = EXCLUDED.fetched_at
            """;

        String deleteStaleSql = "DELETE FROM account_recipes WHERE fetched_at < ?";

        try (Connection con = Db.openConnection()) {
            con.setAutoCommit(false);
            try {
                Timestamp runTs;
                try (PreparedStatement psNow = con.prepareStatement("SELECT now()");
                     ResultSet rs = psNow.executeQuery()) {
                    if (!rs.next()) throw new SQLException("SELECT now() returned no row");
                    runTs = rs.getTimestamp(1);
                }

                try (PreparedStatement psUpsert = con.prepareStatement(upsertSql)) {
                    for (Integer id : ids) {
                        psUpsert.setInt(1, id);
                        psUpsert.setTimestamp(2, runTs);
                        psUpsert.addBatch();
                    }
                    psUpsert.executeBatch();
                }

                try (PreparedStatement psDel = con.prepareStatement(deleteStaleSql)) {
                    psDel.setTimestamp(1, runTs);
                    psDel.executeUpdate();
                }

                con.commit();
            } catch (Exception ex) {
                con.rollback();
                throw ex;
            }
        }
    }
}