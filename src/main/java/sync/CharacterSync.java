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

    /**
     * One character's parsed payload, as the upstream fetch produced it and as
     * {@link #publishRoster(Connection, List, java.time.Instant)} writes it.
     */
    record CharPayload(
            CharacterInfo info,
            List<model.CharacterCraftingRow> crafting,
            List<model.CharacterRecipeRow> recipes,
            List<model.CharacterItemRow> items
    ) {}

    public static void syncCharactersCraftingAndRecipes() throws Exception {
        List<String> names = fetchCharacterNames();
        if (names.isEmpty()) return;

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
            publishRoster(con, payloads, java.time.Instant.now());
        }
    }

    /**
     * Publishes a whole roster and the {@code CHARACTERS} completion marker in one transaction
     * (TARGET_ARCHITECTURE.md §9.1): a concurrent calculation must never read some characters'
     * refreshed rows alongside the previous run's still-current marker, and a failure part-way
     * through must leave the whole last-good generation and its marker intact rather than publishing
     * a half-replaced roster.
     *
     * <p>Separated from the upstream fetch above so the publication protocol itself — including its
     * rollback — is drivable without a GW2 API call (docs/TEST_STRATEGY.md §8).
     *
     * @param completedAt the instant to record as this source's successful completion
     */
    static void publishRoster(Connection con, List<CharPayload> payloads, java.time.Instant completedAt)
            throws Exception {

        con.setAutoCommit(false);
        AccountSyncStateRepository.ensure(con);

        try {
            for (CharPayload p : payloads) {
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
            }

            AccountSyncStateRepository.mark(con, AccountSyncStateRepository.CHARACTERS, completedAt);
            con.commit();

        } catch (Exception ex) {
            con.rollback();
            throw ex;
        }
    }

    /**
     * Fetches and publishes one account's whole roster (STORY-APP-015).
     *
     * <p>Every character is fetched and parsed first, outside any transaction, exactly as the
     * unscoped sequence above does - then the complete roster is published in one transaction.
     *
     * <p>A validated empty roster <em>is</em> published, like every other source
     * (TARGET_ARCHITECTURE.md §7.2 "a successful empty or unchanged source has a valid marker and
     * advances its generation"): a key that lost its {@code characters} permission is not an empty
     * list, it is an HTTP 403 that {@link #fetchCharacterNames()} throws on, and a body that is not
     * an array is rejected by the parser. So an empty {@code /v2/characters} array means this account
     * owns no characters, and the account's character source is then legitimately empty and current.
     *
     * <p>Conversely, a character the roster named whose detail response cannot be parsed is a
     * validation failure, not an absent character - publishing the rest would delete it as stale - so
     * it aborts the whole publication before the transaction opens and leaves the last-good roster,
     * generation and marker untouched.
     */
    public static void syncCharactersCraftingAndRecipes(model.AccountScope scope) throws Exception {
        syncCharactersCraftingAndRecipes(scope, Gw2ApiClient::getAuth);
    }

    static void syncCharactersCraftingAndRecipes(model.AccountScope scope, AccountSync.AuthFetcher fetcher)
            throws Exception {
        AccountSync.requireCredentialOwner(scope, fetcher);
        List<String> names = CharacterNamesParser.parseStrict(fetcher.get("https://api.guildwars2.com/v2/characters"));

        List<CharPayload> payloads = new ArrayList<>(names.size());
        for (String name : names) {
            String enc = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            JsonNode cNode = fetcher.get("https://api.guildwars2.com/v2/characters/" + enc);
            CharacterInfo info = CharacterParser.parseInfo(cNode);
            if (info == null) {
                throw new IllegalStateException(
                        "Unreadable character detail for '" + name + "'; roster not published");
            }

            requireConsistentDetail(name, info, cNode);

            payloads.add(new CharPayload(
                    info,
                    CharacterCraftingParser.parse(CharacterParser.craftingNode(cNode)),
                    CharacterRecipesParser.parse(CharacterParser.recipesNode(cNode)),
                    characterItems(cNode)));
        }

        try (Connection con = Db.open()) {
            publishRoster(con, scope, payloads, java.time.Instant.now());
        }
    }

    /**
     * Publishes one account's roster and every character's payload as one source
     * (TARGET_ARCHITECTURE.md §7.2 "The character source includes the roster and all character
     * payloads as one publication").
     *
     * <p>Three things the account scope changes relative to the unscoped
     * {@link #publishRoster(Connection, List, java.time.Instant)}:
     *
     * <ul>
     *   <li>the roster upsert conflicts on {@code (account_id, name)}, because a character name is
     *       unique within an account and not globally, and it returns the surrogate id that the
     *       composite {@code (account_id, character_id)} parent key makes meaningful only together
     *       with that account;</li>
     *   <li>every child write and every stale-row delete carries both halves of that key, so no
     *       statement can reach a character owned by another account - which the schema's composite
     *       foreign key independently refuses;</li>
     *   <li>characters this run did not refresh are deleted for this account, which is what makes
     *       the roster a replacement rather than an accumulation. Their crafting, recipe and
     *       inventory rows go with them through {@code ON DELETE CASCADE}. An empty {@code payloads}
     *       is therefore an ordinary replacement by an empty roster, and still advances the
     *       generation and records the marker, because the publication succeeded.</li>
     * </ul>
     */
    static repo.AccountSourcePublication.Published publishRoster(Connection con,
                                                                 model.AccountScope scope,
                                                                 List<CharPayload> payloads,
                                                                 java.time.Instant completedAt) throws Exception {
        return new repo.AccountSourcePublication().publish(
                con, scope, AccountSyncStateRepository.CHARACTERS, completedAt,
                (tx, owner, runTs) -> {
                    for (CharPayload p : payloads) {
                        long characterId = upsertScopedCharacter(tx, owner, p.info(), runTs);
                        replaceScopedCrafting(tx, owner, characterId, p.crafting(), runTs);
                        replaceScopedRecipes(tx, owner, characterId, p.recipes(), runTs);
                        replaceScopedItems(tx, owner, characterId, p.items(), runTs);
                    }
                    deleteStaleCharacters(tx, owner, runTs);
                });
    }

    /**
     * Rejects a detail response that cannot replace the requested character's last-good rows: one
     * naming a different character, or one missing a section (a key without the matching
     * permission omits it) that the parsers would otherwise read as "empty" and publish as deletion.
     */
    static void requireConsistentDetail(String requestedName, CharacterInfo info, JsonNode cNode) {
        if (!requestedName.equals(info.name())) {
            throw new IllegalStateException("Character detail for '" + requestedName
                    + "' names '" + info.name() + "'; roster not published");
        }
        for (String section : new String[] {"crafting", "recipes", "bags", "equipment"}) {
            JsonNode n = cNode.get(section);
            if (n == null || !n.isArray()) {
                throw new IllegalStateException("Character detail for '" + requestedName
                        + "' lacks a '" + section + "' array; roster not published");
            }
        }
        for (JsonNode row : cNode.get("crafting")) {
            if (row == null || !row.isObject() || !row.path("discipline").isTextual()
                    || row.path("discipline").asText().isBlank() || !nonNegativeInt(row.path("rating"))) {
                throw malformed(requestedName, "crafting");
            }
        }
        for (JsonNode id : cNode.get("recipes")) {
            if (!positiveInt(id)) throw malformed(requestedName, "recipes");
        }
        for (JsonNode bag : cNode.get("bags")) {
            if (bag == null || bag.isNull()) continue; // an unequipped bag slot
            JsonNode inv = bag.get("inventory");
            if (!bag.isObject() || inv == null || !inv.isArray()) throw malformed(requestedName, "bags");
            for (JsonNode slot : inv) {
                if (!validItem(slot, true)) throw malformed(requestedName, "bags");
            }
        }
        for (JsonNode item : cNode.get("equipment")) {
            if (!validItem(item, false) || item == null || item.isNull()
                    || !item.path("slot").isTextual() || item.path("slot").asText().isBlank()) {
                throw malformed(requestedName, "equipment");
            }
        }
    }

    private static IllegalStateException malformed(String name, String section) {
        return new IllegalStateException("Character detail for '" + name + "' has a malformed '"
                + section + "' entry; roster not published");
    }

    /** An empty inventory slot (null) is valid; any other slot must be an object with an integral id. */
    private static boolean validItem(JsonNode n, boolean nullAllowed) {
        if (n == null || n.isNull()) return nullAllowed;
        return n.isObject() && positiveInt(n.get("id"))
                && (nullAllowed ? positiveInt(n.get("count"))
                : !n.has("count") || positiveInt(n.get("count")));
    }

    private static boolean nonNegativeInt(JsonNode n) {
        return n != null && n.isIntegralNumber() && n.canConvertToInt() && n.intValue() >= 0;
    }

    private static boolean positiveInt(JsonNode n) {
        return nonNegativeInt(n) && n.intValue() > 0;
    }

    private static List<model.CharacterItemRow> characterItems(JsonNode cNode) {
        List<model.CharacterItemRow> itemRows = new ArrayList<>();
        itemRows.addAll(CharacterItemsParser.parseBags(CharacterParser.bagsNode(cNode)));
        itemRows.addAll(CharacterItemsParser.parseEquipment(CharacterParser.equipmentNode(cNode)));
        return itemRows;
    }

    private static long upsertScopedCharacter(Connection con, model.AccountScope scope,
                                              CharacterInfo c, Timestamp runTs) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO characters
                  (account_id, name, profession, race, gender, level, created_at_gw, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?)
                ON CONFLICT (account_id, name) DO UPDATE SET
                  profession    = EXCLUDED.profession,
                  race          = EXCLUDED.race,
                  gender        = EXCLUDED.gender,
                  level         = EXCLUDED.level,
                  created_at_gw = EXCLUDED.created_at_gw,
                  fetched_at    = EXCLUDED.fetched_at
                RETURNING character_id
                """)) {
            ps.setObject(1, scope.accountId());
            DbBind.setStringOrNull(ps, 2, c.name());
            DbBind.setStringOrNull(ps, 3, c.profession());
            DbBind.setStringOrNull(ps, 4, c.race());
            DbBind.setStringOrNull(ps, 5, c.gender());
            DbBind.setIntOrNull(ps, 6, c.level());
            DbBind.setStringOrNull(ps, 7, c.createdIso());
            ps.setTimestamp(8, runTs);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Upsert character failed (no RETURNING row)");
                return rs.getLong(1);
            }
        }
    }

    private static void replaceScopedCrafting(Connection con, model.AccountScope scope, long characterId,
                                              List<model.CharacterCraftingRow> rows, Timestamp runTs)
            throws SQLException {
        if (rows != null && !rows.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO character_crafting
                      (account_id, character_id, discipline, rating, is_active, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (account_id, character_id, discipline) DO UPDATE SET
                      rating     = EXCLUDED.rating,
                      is_active  = EXCLUDED.is_active,
                      fetched_at = EXCLUDED.fetched_at
                    """)) {
                for (var row : rows) {
                    ps.setObject(1, scope.accountId());
                    ps.setLong(2, characterId);
                    ps.setString(3, row.discipline());
                    ps.setInt(4, row.rating());
                    ps.setBoolean(5, row.active());
                    ps.setTimestamp(6, runTs);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        deleteStaleChildren(con, scope, "character_crafting", characterId, runTs);
    }

    private static void replaceScopedRecipes(Connection con, model.AccountScope scope, long characterId,
                                             List<model.CharacterRecipeRow> rows, Timestamp runTs)
            throws SQLException {
        if (rows != null && !rows.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO character_recipes (account_id, character_id, recipe_id, fetched_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (account_id, character_id, recipe_id) DO UPDATE SET
                      fetched_at = EXCLUDED.fetched_at
                    """)) {
                for (var row : rows) {
                    ps.setObject(1, scope.accountId());
                    ps.setLong(2, characterId);
                    ps.setInt(3, row.recipeId());
                    ps.setTimestamp(4, runTs);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        deleteStaleChildren(con, scope, "character_recipes", characterId, runTs);
    }

    private static void replaceScopedItems(Connection con, model.AccountScope scope, long characterId,
                                           List<model.CharacterItemRow> rows, Timestamp runTs)
            throws SQLException {
        if (rows != null && !rows.isEmpty()) {
            // The conflict target names the unique constraint rather than the column list, because
            // the migrated constraint is UNIQUE NULLS NOT DISTINCT: inferring it from columns would
            // not express that one bag or equipment slot holds at most one row whichever of its
            // location components are NULL (STORY-INFRA-004, TARGET_ARCHITECTURE.md §7.2).
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO character_items
                      (account_id, character_id, location, bag_index, slot_index, equipment_slot,
                       item_id, count, binding, bound_to, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT uq_character_items_slot DO UPDATE SET
                      item_id    = EXCLUDED.item_id,
                      count      = EXCLUDED.count,
                      binding    = EXCLUDED.binding,
                      bound_to   = EXCLUDED.bound_to,
                      fetched_at = EXCLUDED.fetched_at
                    """)) {
                for (var row : rows) {
                    ps.setObject(1, scope.accountId());
                    ps.setLong(2, characterId);
                    ps.setString(3, row.location());
                    DbBind.setIntOrNull(ps, 4, row.bagIndex());
                    DbBind.setIntOrNull(ps, 5, row.slotIndex());
                    DbBind.setStringOrNull(ps, 6, row.equipmentSlot());
                    ps.setInt(7, row.itemId());
                    // count is NOT NULL DEFAULT 1; the API omits "count" for a singular item.
                    ps.setInt(8, row.count() != null ? row.count() : 1);
                    DbBind.setStringOrNull(ps, 9, row.binding());
                    DbBind.setStringOrNull(ps, 10, row.boundTo());
                    ps.setTimestamp(11, runTs);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        deleteStaleChildren(con, scope, "character_items", characterId, runTs);
    }

    /** The table name is one of this class's own three literals, never caller input. */
    private static void deleteStaleChildren(Connection con, model.AccountScope scope, String table,
                                            long characterId, Timestamp runTs) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + table
                + " WHERE account_id = ? AND character_id = ? AND fetched_at < ?")) {
            ps.setObject(1, scope.accountId());
            ps.setLong(2, characterId);
            ps.setTimestamp(3, runTs);
            ps.executeUpdate();
        }
    }

    private static void deleteStaleCharacters(Connection con, model.AccountScope scope, Timestamp runTs)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM characters WHERE account_id = ? AND fetched_at < ?")) {
            ps.setObject(1, scope.accountId());
            ps.setTimestamp(2, runTs);
            ps.executeUpdate();
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
