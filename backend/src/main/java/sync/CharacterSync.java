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
     * {@code publishRoster} writes it.
     */
    record CharPayload(
            CharacterInfo info,
            List<model.CharacterCraftingRow> crafting,
            List<model.CharacterRecipeRow> recipes,
            List<model.CharacterItemRow> items,
            List<model.CharacterBagRow> bags
    ) {
        /** Payload without bag-manifest evidence ({@code bags == null}), never a known-empty layout. */
        CharPayload(CharacterInfo info, List<model.CharacterCraftingRow> crafting,
                    List<model.CharacterRecipeRow> recipes, List<model.CharacterItemRow> items) {
            this(info, crafting, recipes, items, null);
        }
    }

    /**
     * Fetches and publishes one account's whole roster.
     *
     * <p>Every character is fetched and parsed first, outside any transaction, then the complete
     * roster is published in one transaction.
     *
     * <p>A validated empty roster <em>is</em> published, like every other source
     * (a successful empty or unchanged source has a valid marker and
     * advances its generation). Permission failures propagate through the supplied
     * {@link AccountSync.AuthFetcher}; a body that is not an array is rejected by the strict names
     * parser. So an empty {@code /v2/characters} array means this account
     * owns no characters, and the account's character source is then legitimately empty and current.
     *
     * <p>Conversely, a character the roster named whose detail response cannot be parsed is a
     * validation failure, not an absent character - publishing the rest would delete it as stale - so
     * it aborts the whole publication before the transaction opens and leaves the last-good roster,
     * generation and marker untouched.
     */
    public static void syncCharactersCraftingAndRecipes(model.AccountScope scope, AccountSync.AuthFetcher fetcher)
            throws Exception {
        syncCharactersCraftingAndRecipes(scope, fetcher, ids -> {});
    }

    static void syncCharactersCraftingAndRecipes(model.AccountScope scope, AccountSync.AuthFetcher fetcher,
                                                IconSync.ItemMetadataRepair metadata) throws Exception {
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
                    characterItems(cNode),
                    parseManifest(name, cNode)));
        }

        metadata.repair(payloads.stream().flatMap(p -> java.util.stream.Stream.concat(
                        p.items().stream().flatMap(item -> java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(item.itemId()), item.upgradeIds().stream())),
                        p.bags() == null ? java.util.stream.Stream.<Integer>empty()
                                : p.bags().stream().map(model.CharacterBagRow::containerItemId)
                                        .filter(java.util.Objects::nonNull)))
                .distinct().toList());
        try (Connection con = Db.open()) {
            publishRoster(con, scope, payloads, java.time.Clock.systemUTC());
        }
    }

    /**
     * Publishes one account's roster and every character's payload as one source.
     *
     * <p>Three things the account scope guarantees:
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
                                                                 java.time.Clock clock) throws Exception {
        // Reject a contradictory manifest/occupied set before any write or generation advance.
        boolean complete = true;
        for (CharPayload p : payloads) {
            if (p.bags() == null) complete = false;
            else validateBagPayload(p);
        }
        final boolean layoutComplete = complete;
        return new repo.AccountSourcePublication(clock).publish(
                con, scope, AccountSyncStateRepository.CHARACTERS,
                (tx, owner, runTs) -> {
                    for (CharPayload p : payloads) {
                        long characterId = upsertScopedCharacter(tx, owner, p.info(), runTs);
                        replaceScopedCrafting(tx, owner, characterId, p.crafting(), runTs);
                        replaceScopedRecipes(tx, owner, characterId, p.recipes(), runTs);
                        replaceScopedItems(tx, owner, characterId, p.items(), runTs);
                        replaceScopedBags(tx, owner, characterId, p.bags(), runTs);
                    }
                    if (layoutComplete) markBagLayoutPublished(tx, owner, runTs);
                    else clearBagLayoutPublished(tx, owner);
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
        for (String field : new String[] {"profession", "race", "gender"}) {
            JsonNode v = cNode.get(field);
            if (v == null || !v.isTextual() || v.asText().isBlank()) throw malformedMeta(requestedName, field);
        }
        if (!positiveInt(cNode.get("level"))) throw malformedMeta(requestedName, "level");
        JsonNode created = cNode.get("created");
        if (created == null || !created.isTextual()) throw malformedMeta(requestedName, "created");
        try {
            java.time.OffsetDateTime.parse(created.asText());
        } catch (java.time.format.DateTimeParseException e) {
            throw malformedMeta(requestedName, "created");
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
        parseManifest(requestedName, cNode);
        for (JsonNode item : cNode.get("equipment")) {
            if (!validItem(item, false) || item == null || item.isNull()
                    || !item.path("slot").isTextual() || item.path("slot").asText().isBlank()) {
                throw malformed(requestedName, "equipment");
            }
        }
    }

    private static List<model.CharacterBagRow> parseManifest(String name, JsonNode cNode) {
        try {
            return CharacterBagManifestParser.parse(CharacterParser.bagsNode(cNode));
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Character detail for '" + name + "' has an invalid bag layout ("
                    + e.getMessage() + "); roster not published");
        }
    }

    private static void replaceScopedBags(Connection con, model.AccountScope scope, long characterId,
                                          List<model.CharacterBagRow> rows, Timestamp runTs) throws SQLException {
        if (rows != null && !rows.isEmpty()) {
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO character_bags
                      (account_id, character_id, bag_index, installed, container_item_id, capacity, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (account_id, character_id, bag_index) DO UPDATE SET
                      installed         = EXCLUDED.installed,
                      container_item_id = EXCLUDED.container_item_id,
                      capacity          = EXCLUDED.capacity,
                      fetched_at        = EXCLUDED.fetched_at
                    """)) {
                for (var row : rows) {
                    ps.setObject(1, scope.accountId());
                    ps.setLong(2, characterId);
                    ps.setInt(3, row.bagIndex());
                    ps.setBoolean(4, row.installed());
                    DbBind.setIntOrNull(ps, 5, row.containerItemId());
                    DbBind.setIntOrNull(ps, 6, row.capacity());
                    ps.setTimestamp(7, runTs);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        deleteStaleChildren(con, scope, "character_bags", characterId, runTs);
    }

    /** Validates normalized manifest rows and occupied BAG rows of one character. */
    static void validateBagPayload(CharPayload p) {
        String who = "Character '" + p.info().name() + "'";
        java.util.Map<Integer, model.CharacterBagRow> bags = new java.util.HashMap<>();
        for (model.CharacterBagRow b : p.bags()) {
            if (b.bagIndex() < 0 || bags.put(b.bagIndex(), b) != null) {
                throw new IllegalStateException(who + " has a negative or duplicate bag position; roster not published");
            }
            boolean ok = b.installed()
                    ? b.containerItemId() != null && b.containerItemId() > 0 && b.capacity() != null && b.capacity() > 0
                    : b.containerItemId() == null && b.capacity() == null;
            if (!ok) throw new IllegalStateException(who + " has an inconsistent bag " + b.bagIndex()
                    + " manifest row; roster not published");
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (model.CharacterItemRow it : p.items()) {
            if (!"BAG".equals(it.location())) continue;
            if (it.bagIndex() == null || it.slotIndex() == null || it.slotIndex() < 0
                    || it.count() == null || it.count() <= 0 || it.itemId() <= 0) {
                throw new IllegalStateException(who + " has an invalid occupied bag row; roster not published");
            }
            model.CharacterBagRow b = bags.get(it.bagIndex());
            if (b == null || !b.installed() || it.slotIndex() >= b.capacity()) {
                throw new IllegalStateException(who + " has an occupied slot outside an installed bag's capacity; roster not published");
            }
            if (!seen.add(((long) it.bagIndex() << 32) | it.slotIndex())) {
                throw new IllegalStateException(who + " has a duplicate occupied slot; roster not published");
            }
        }
    }

    private static void clearBagLayoutPublished(Connection con, model.AccountScope scope) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM character_bag_layout_publication WHERE account_id = ?")) {
            ps.setObject(1, scope.accountId());
            ps.executeUpdate();
        }
    }

    /** Records that this CHARACTERS publication wrote a complete manifest (absent = legacy generation). */
    private static void markBagLayoutPublished(Connection con, model.AccountScope scope, Timestamp runTs)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO character_bag_layout_publication (account_id, fetched_at) VALUES (?, ?)
                ON CONFLICT (account_id) DO UPDATE SET fetched_at = EXCLUDED.fetched_at
                """)) {
            ps.setObject(1, scope.accountId());
            ps.setTimestamp(2, runTs);
            ps.executeUpdate();
        }
    }

    private static IllegalStateException malformedMeta(String name, String field) {
        return new IllegalStateException("Character detail for '" + name + "' has a missing or malformed '"
                + field + "' field; roster not published");
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
                  (account_id, name, profession, race, gender, level, created_at_gw, fetched_at, age_seconds, deaths)
                VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?, ?, ?)
                ON CONFLICT (account_id, name) DO UPDATE SET
                  profession    = EXCLUDED.profession,
                  race          = EXCLUDED.race,
                  gender        = EXCLUDED.gender,
                  level         = EXCLUDED.level,
                  created_at_gw = EXCLUDED.created_at_gw,
                  fetched_at    = EXCLUDED.fetched_at,
                  age_seconds   = EXCLUDED.age_seconds,
                  deaths        = EXCLUDED.deaths
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
            ps.setObject(9, c.ageSeconds(), java.sql.Types.BIGINT);
            ps.setObject(10, c.deaths(), java.sql.Types.BIGINT);
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
            // location components are NULL.
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO character_items
                      (account_id, character_id, location, bag_index, slot_index, equipment_slot,
                       item_id, count, binding, bound_to, fetched_at, upgrade_ids)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT uq_character_items_slot DO UPDATE SET
                      item_id    = EXCLUDED.item_id,
                      count      = EXCLUDED.count,
                      binding    = EXCLUDED.binding,
                      bound_to   = EXCLUDED.bound_to,
                      upgrade_ids = EXCLUDED.upgrade_ids,
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
                    ps.setArray(12, con.createArrayOf("integer", row.upgradeIds().toArray()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        deleteStaleChildren(con, scope, "character_items", characterId, runTs);
    }

    /** The table name is a fixed internal literal, never caller input. */
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
}
