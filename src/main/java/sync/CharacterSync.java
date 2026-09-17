package sync;

import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import model.CharacterInfo;
import parser.*;

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
                List<model.CharacterRecipeRow> recipes
        ) {}

        List<CharPayload> payloads = new ArrayList<>(names.size());

        for (String name : names) {
            JsonNode cNode = fetchCharacterDetails(name);

            CharacterInfo info = CharacterParser.parseInfo(cNode);
            if (info == null) continue;

            var craftingRows = CharacterCraftingParser.parse(CharacterParser.craftingNode(cNode));
            var recipeRows   = CharacterRecipesParser.parse(CharacterParser.recipesNode(cNode));

            payloads.add(new CharPayload(info, craftingRows, recipeRows));
        }

        if (payloads.isEmpty()) return;

        try (Connection con = Db.openConnection()) {
            con.setAutoCommit(false);

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

                    con.commit();

                } catch (Exception ex) {
                    con.rollback();
                    throw ex;
                }
            }
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

    private static long upsertCharacter(Connection con, CharacterInfo c) throws SQLException {

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

    private static void replaceCharacterCrafting(Connection con,
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
}