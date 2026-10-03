package sync;

import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import model.CharacterInfo;
import parser.*;
import repo.Db;
import repo.AccountSyncStateRepository;

import util.DbBind;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class CharacterSync {

    private CharacterSync() {}

    public static void syncCharactersCraftingAndRecipes() throws Exception {
        List<String> names = fetchCharacterNames();
        if (names.isEmpty()) return;

        record CharPayload(
                CharacterInfo info,
                List<model.CharacterCraftingRow> crafting,
                List<model.CharacterRecipeRow> recipes,
                List<model.CharacterItemRow> items
        ) {}

        List<CharPayload> payloads = new ArrayList<>(names.size());

        for (String name : names) {
            JsonNode cNode = fetchCharacterDetails(name);

            CharacterInfo info = CharacterParser.parseInfo(cNode);
            if (info == null) continue;

            var craftingRows = CharacterCraftingParser.parse(CharacterParser.craftingNode(cNode));
            var recipeRows   = CharacterRecipesParser.parse(CharacterParser.recipesNode(cNode));

            List<model.CharacterItemRow> itemRows = new ArrayList<>();
            itemRows.addAll(CharacterItemsParser.parseBags(CharacterParser.bagsNode(cNode)));
            itemRows.addAll(CharacterItemsParser.parseEquipment(CharacterParser.equipmentNode(cNode)));

            payloads.add(new CharPayload(info, craftingRows, recipeRows, itemRows));
        }

        if (payloads.isEmpty()) return;

        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            AccountSyncStateRepository.ensure(con);

            for (CharPayload p : payloads) {
                try {
                    long characterId = upsertCharacter(con, p.info());

                    Timestamp runTs;
                    try (PreparedStatement psNow = con.prepareStatement("SELECT now()");
                         ResultSet rs = psNow.executeQuery()) {
                        if (!rs.next()) throw new SQLException("SELECT now() returned no row");
                        runTs = rs.getTimestamp(1);
                    }

                    replaceCharacterCrafting(con, characterId, p.crafting(), runTs);
                    replaceCharacterRecipes(con, characterId, p.recipes(), runTs);
                    replaceCharacterItems(con, characterId, p.items(), runTs);

                    con.commit();

                } catch (Exception ex) {
                    con.rollback();
                    throw ex;
                }
            }
            AccountSyncStateRepository.mark(con, AccountSyncStateRepository.CHARACTERS,
                    java.time.Instant.now());
            con.commit();
        }
    }

    public static List<String> fetchCharacterNames() throws Exception {
        String url = "https://api.guildwars2.com/v2/characters";
        JsonNode root = Gw2ApiClient.getAuth(url);
        return CharacterNamesParser.parse(root);
    }

    public static JsonNode fetchCharacterDetails(String characterName) throws Exception {
        String enc = URLEncoder.encode(characterName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "https://api.guildwars2.com/v2/characters/" + enc;
        return Gw2ApiClient.getAuth(url);
    }

    static long upsertCharacter(Connection con, CharacterInfo c) throws SQLException {

        String sql = """
        INSERT INTO characters (name, profession, race, gender, level, created_at_gw, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?::timestamptz, now())
        ON CONFLICT (name) DO UPDATE SET
          profession    = EXCLUDED.profession,
          race          = EXCLUDED.race,
          gender        = EXCLUDED.gender,
          level         = EXCLUDED.level,
          created_at_gw = EXCLUDED.created_at_gw,
          fetched_at    = EXCLUDED.fetched_at
        RETURNING character_id
        """;

        try (PreparedStatement ps = con.prepareStatement(sql)) {

            DbBind.setStringOrNull(ps, 1, c.name());
            DbBind.setStringOrNull(ps, 2, c.profession());
            DbBind.setStringOrNull(ps, 3, c.race());
            DbBind.setStringOrNull(ps, 4, c.gender());

            DbBind.setIntOrNull(ps, 5, c.level());
            DbBind.setStringOrNull(ps, 6, c.createdIso());

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Upsert character failed (no RETURNING row)");
                return rs.getLong(1);
            }
        }
    }

    static void replaceCharacterCrafting(Connection con,
                                                 long characterId,
                                                 List<model.CharacterCraftingRow> rows,
                                                 Timestamp runTs) throws SQLException {

        String upsertSql = """
        INSERT INTO character_crafting (character_id, discipline, rating, is_active, fetched_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (character_id, discipline) DO UPDATE SET
          rating     = EXCLUDED.rating,
          is_active  = EXCLUDED.is_active,
          fetched_at = EXCLUDED.fetched_at
        """;

        String deleteStaleSql = """
        DELETE FROM character_crafting
        WHERE character_id = ?
          AND fetched_at < ?
        """;

        if (rows != null && !rows.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement(upsertSql)) {

                for (var row : rows) {
                    ps.setLong(1, characterId);
                    ps.setString(2, row.discipline());
                    ps.setInt(3, row.rating());
                    ps.setBoolean(4, row.active());
                    ps.setTimestamp(5, runTs);
                    ps.addBatch();
                }

                ps.executeBatch();
            }
        }

        try (PreparedStatement psDel = con.prepareStatement(deleteStaleSql)) {
            psDel.setLong(1, characterId);
            psDel.setTimestamp(2, runTs);
            psDel.executeUpdate();
        }
    }

    private static void replaceCharacterRecipes(Connection con,
                                                long characterId,
                                                List<model.CharacterRecipeRow> rows,
                                                Timestamp runTs) throws SQLException {

        String upsertSql = """
        INSERT INTO character_recipes (character_id, recipe_id, fetched_at)
        VALUES (?, ?, ?)
        ON CONFLICT (character_id, recipe_id) DO UPDATE SET
          fetched_at = EXCLUDED.fetched_at
        """;

        String deleteStaleSql = """
        DELETE FROM character_recipes
        WHERE character_id = ?
          AND fetched_at < ?
        """;

        if (rows != null && !rows.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement(upsertSql)) {

                for (var row : rows) {
                    ps.setLong(1, characterId);
                    ps.setInt(2, row.recipeId());
                    ps.setTimestamp(3, runTs);
                    ps.addBatch();
                }

                ps.executeBatch();
            }
        }

        try (PreparedStatement psDel = con.prepareStatement(deleteStaleSql)) {
            psDel.setLong(1, characterId);
            psDel.setTimestamp(2, runTs);
            psDel.executeUpdate();
        }
    }

    static void replaceCharacterItems(Connection con,
                                              long characterId,
                                              List<model.CharacterItemRow> rows,
                                              Timestamp runTs) throws SQLException {

        String upsertSql = """
        INSERT INTO character_items
          (character_id, location, bag_index, slot_index, equipment_slot, item_id, count, binding, bound_to, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (character_id, location, bag_index, slot_index, equipment_slot) DO UPDATE SET
          item_id    = EXCLUDED.item_id,
          count      = EXCLUDED.count,
          binding    = EXCLUDED.binding,
          bound_to   = EXCLUDED.bound_to,
          fetched_at = EXCLUDED.fetched_at
        """;

        String deleteStaleSql = """
        DELETE FROM character_items
        WHERE character_id = ?
          AND fetched_at < ?
        """;

        if (rows != null && !rows.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement(upsertSql)) {

                for (var row : rows) {
                    ps.setLong(1, characterId);
                    ps.setString(2, row.location());
                    DbBind.setIntOrNull(ps, 3, row.bagIndex());
                    DbBind.setIntOrNull(ps, 4, row.slotIndex());
                    DbBind.setStringOrNull(ps, 5, row.equipmentSlot());
                    ps.setInt(6, row.itemId());
                    // count column is NOT NULL DEFAULT 1; the API omits "count" for a singular item.
                    ps.setInt(7, row.count() != null ? row.count() : 1);
                    DbBind.setStringOrNull(ps, 8, row.binding());
                    DbBind.setStringOrNull(ps, 9, row.boundTo());
                    ps.setTimestamp(10, runTs);
                    ps.addBatch();
                }

                ps.executeBatch();
            }
        }

        try (PreparedStatement psDel = con.prepareStatement(deleteStaleSql)) {
            psDel.setLong(1, characterId);
            psDel.setTimestamp(2, runTs);
            psDel.executeUpdate();
        }
    }
}
