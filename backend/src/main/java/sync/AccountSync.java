package sync;

import com.fasterxml.jackson.databind.JsonNode;
import model.BankSlot;
import model.MaterialStack;
import parser.BankParser;
import parser.MaterialParser;
import parser.RecipeIdParser;
import repo.Db;
import repo.AccountLuckRepository;
import repo.AccountSyncStateRepository;
import util.DbBind;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class AccountSync {
    private AccountSync() {}

    private static repo.AccountSourcePublication publication(java.time.Clock clock) {
        return new repo.AccountSourcePublication(clock);
    }

    /**
     * Fetches and publishes one account's bank.
     *
     * <p>The upstream call and the parse happen first and outside any transaction, so a slow or
     * failing GW2 API never holds a database transaction open. Only once the whole payload is in hand does
     * {@link repo.AccountSourcePublication} open the transaction that replaces the rows, removes the
     * ones this run did not refresh, advances the {@code BANK} generation and records the account's
     * successful-sync activity - all or nothing.
     */
    public static void syncAccountBank(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        syncAccountBankImpl(scope, fetcher, ids -> {});
    }

    /** Fetch seam: an authenticated GET bound to one credential, replaceable by an offline fake. */
    @FunctionalInterface
    public interface AuthFetcher {
        JsonNode get(String url) throws Exception;
    }

    /**
     * Proves the credential behind {@code fetcher} belongs to {@code scope} before any source data
     * is fetched or published. An unavailable, malformed or different account id refuses the whole
     * publication; the scope is never rewritten to the upstream id and no default account is used.
     */
    static void requireCredentialOwner(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        String upstreamAccountId = parseAccountId(fetcher.get("https://api.guildwars2.com/v2/account"));
        if (!scope.id().equalsIgnoreCase(upstreamAccountId)) {
            throw new IllegalStateException(
                    "The supplied credential resolves to a different Guild Wars 2 account than the"
                            + " requested scope, so no account data was written");
        }
    }

    /** {@code metadata} repairs missing item metadata after the fetch and before the publication transaction. */
    static void syncAccountBankImpl(model.AccountScope scope, AuthFetcher fetcher, IconSync.ItemMetadataRepair metadata)
            throws Exception {
        requireCredentialOwner(scope, fetcher);
        List<BankSlot> rows = parseBankSlots(fetcher.get("https://api.guildwars2.com/v2/account/bank"));
        metadata.repair(rows.stream().map(BankSlot::itemId).toList());
        try (Connection con = Db.open()) {
            publishBank(con, scope, rows, java.time.Clock.systemUTC());
        }
    }

    /** Publishes already-fetched bank slots; see {@link #syncAccountBank(model.AccountScope, AuthFetcher)}. */
    static repo.AccountSourcePublication.Published publishBank(Connection con,
                                                               model.AccountScope scope,
                                                               List<BankSlot> rows,
                                                               java.time.Clock clock) throws Exception {
        return publication(clock).publish(con, scope, AccountSyncStateRepository.BANK,
                (tx, owner, runTs) -> upsertScopedAccountBank(tx, owner, rows, runTs));
    }

    /** Fetches and publishes one account's material storage; see {@link #syncAccountBank(model.AccountScope, AuthFetcher)}. */
    public static void syncAccountMaterials(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        syncAccountMaterialsImpl(scope, fetcher, ids -> {});
    }

    static void syncAccountMaterialsImpl(model.AccountScope scope, AuthFetcher fetcher, IconSync.ItemMetadataRepair metadata)
            throws Exception {
        requireCredentialOwner(scope, fetcher);
        List<MaterialStack> rows = parseMaterialStacks(fetcher.get("https://api.guildwars2.com/v2/account/materials"));
        metadata.repair(rows.stream().map(MaterialStack::itemId).toList());
        try (Connection con = Db.open()) {
            publishMaterials(con, scope, rows, java.time.Clock.systemUTC());
        }
    }

    /**
     * Publishes already-fetched material stacks. Writes no {@code account_materials_sync} row: that
     * singleton is retired as a runtime authority in favour of this account's {@code MATERIALS} row
     * in {@code account_source_state}.
     */
    static repo.AccountSourcePublication.Published publishMaterials(Connection con,
                                                                    model.AccountScope scope,
                                                                    List<MaterialStack> rows,
                                                                    java.time.Clock clock) throws Exception {
        return publication(clock).publish(con, scope, AccountSyncStateRepository.MATERIALS,
                (tx, owner, runTs) -> upsertScopedAccountMaterials(tx, owner, rows, runTs));
    }

    /** Fetches and publishes one account's recipe unlocks; see {@link #syncAccountBank(model.AccountScope, AuthFetcher)}. */
    public static void syncAccountRecipes(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        requireCredentialOwner(scope, fetcher);
        List<Integer> ids = parseAccountRecipeIds(fetcher.get("https://api.guildwars2.com/v2/account/recipes"));
        try (Connection con = Db.open()) {
            publishRecipes(con, scope, ids, java.time.Clock.systemUTC());
        }
    }

    /** Publishes already-fetched account recipe ids; see {@link #syncAccountBank(model.AccountScope, AuthFetcher)}. */
    static repo.AccountSourcePublication.Published publishRecipes(Connection con,
                                                                  model.AccountScope scope,
                                                                  List<Integer> ids,
                                                                  java.time.Clock clock) throws Exception {
        return publication(clock).publish(con, scope, AccountSyncStateRepository.RECIPES,
                (tx, owner, runTs) -> upsertScopedAccountRecipes(tx, owner, ids, runTs));
    }

    /**
     * Fetches and publishes one account's consumed Luck; see
     * {@link #syncAccountBank(model.AccountScope, AuthFetcher)}.
     *
     * <p>The upstream {@code /v2/account} identifier is validated against {@code scope} before
     * anything is written: a key that resolves to a different account must not write under the
     * caller's GUID, and the publication is refused rather than silently rescoped.
     */
    public static void syncAccountLuck(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        requireCredentialOwner(scope, fetcher);
        long consumedLuck = parseConsumedLuck(fetcher.get("https://api.guildwars2.com/v2/account/luck"));
        try (Connection con = Db.open()) {
            publishLuck(con, scope, consumedLuck, java.time.Clock.systemUTC());
        }
    }

    /** Publishes already-fetched consumed Luck; see {@link #syncAccountLuck(model.AccountScope, AuthFetcher)}. */
    static repo.AccountSourcePublication.Published publishLuck(Connection con,
                                                               model.AccountScope scope,
                                                               long consumedLuck,
                                                               java.time.Clock clock) throws Exception {
        return publication(clock).publish(con, scope, AccountSyncStateRepository.LUCK,
                (tx, owner, runTs) -> new AccountLuckRepository()
                        .save(tx, owner, consumedLuck, runTs.toInstant()));
    }

    private static void upsertScopedAccountBank(Connection con, model.AccountScope scope,
                                                List<BankSlot> rows, Timestamp runTs) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO account_bank
                  (account_id, slot, item_id, count, binding, bound_to, charges, stats_id, stats_attrs, fetched_at)
                VALUES
                  (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (account_id, slot) DO UPDATE SET
                  item_id     = EXCLUDED.item_id,
                  count       = EXCLUDED.count,
                  binding     = EXCLUDED.binding,
                  bound_to    = EXCLUDED.bound_to,
                  charges     = EXCLUDED.charges,
                  stats_id    = EXCLUDED.stats_id,
                  stats_attrs = EXCLUDED.stats_attrs::jsonb,
                  fetched_at  = EXCLUDED.fetched_at
                """)) {
            for (BankSlot row : rows) {
                ps.setObject(1, scope.accountId());
                ps.setInt(2, row.slot());
                DbBind.setIntOrNull(ps, 3, row.itemId());
                DbBind.setIntOrNull(ps, 4, row.count());
                DbBind.setStringOrNull(ps, 5, row.binding());
                DbBind.setStringOrNull(ps, 6, row.boundTo());
                DbBind.setIntOrNull(ps, 7, row.charges());
                DbBind.setIntOrNull(ps, 8, row.statsId());
                DbBind.setStringOrNull(ps, 9, row.statsAttrsJson());
                ps.setTimestamp(10, runTs);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        deleteStale(con, scope, "account_bank", runTs);
    }

    private static void upsertScopedAccountMaterials(Connection con, model.AccountScope scope,
                                                     List<MaterialStack> rows, Timestamp runTs) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO account_materials (account_id, item_id, category, count, binding, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, item_id) DO UPDATE SET
                  category   = EXCLUDED.category,
                  count      = EXCLUDED.count,
                  binding    = EXCLUDED.binding,
                  fetched_at = EXCLUDED.fetched_at
                """)) {
            for (MaterialStack row : rows) {
                ps.setObject(1, scope.accountId());
                ps.setInt(2, row.itemId());
                ps.setInt(3, row.category());
                ps.setInt(4, row.count());
                DbBind.setStringOrNull(ps, 5, row.binding());
                ps.setTimestamp(6, runTs);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        deleteStale(con, scope, "account_materials", runTs);
    }

    private static void upsertScopedAccountRecipes(Connection con, model.AccountScope scope,
                                                   List<Integer> ids, Timestamp runTs) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO account_recipes (account_id, recipe_id, fetched_at)
                VALUES (?, ?, ?)
                ON CONFLICT (account_id, recipe_id) DO UPDATE SET fetched_at = EXCLUDED.fetched_at
                """)) {
            for (Integer id : ids) {
                ps.setObject(1, scope.accountId());
                ps.setInt(2, id);
                ps.setTimestamp(3, runTs);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        deleteStale(con, scope, "account_recipes", runTs);
    }

    /**
     * Removes the rows this run did not refresh, for this account only.
     *
     * <p>A stale-row delete is exactly as dangerous as an unscoped read: without the account
     * predicate it would drop every other account's rows as "not refreshed by this run". The table
     * name is one of this class's own three literals, never caller input.
     */
    private static void deleteStale(Connection con, model.AccountScope scope, String table, Timestamp runTs)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM " + table + " WHERE account_id = ? AND fetched_at < ?")) {
            ps.setObject(1, scope.accountId());
            ps.setTimestamp(2, runTs);
            ps.executeUpdate();
        }
    }

    static List<BankSlot> parseBankSlots(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new RuntimeException("Unexpected JSON (bank): not an array");
        }
        List<BankSlot> rows = new ArrayList<>(root.size());
        for (int slot = 0; slot < root.size(); slot++) {
            JsonNode entry = root.get(slot);
            if (entry != null && !entry.isNull()
                    && (!entry.isObject() || !isInt(entry.get("id")) || entry.get("id").intValue() <= 0
                    || !isInt(entry.get("count")) || entry.get("count").intValue() <= 0)) {
                throw new RuntimeException("Unexpected JSON (bank): malformed slot " + slot + "; nothing published");
            }
            rows.add(BankParser.parseSlot(slot, entry));
        }
        return rows;
    }

    private static boolean isInt(JsonNode n) {
        return n != null && n.isIntegralNumber() && n.canConvertToInt();
    }

    static List<MaterialStack> parseMaterialStacks(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new RuntimeException("Unexpected JSON (materials): not an array");
        }
        List<MaterialStack> rows = new ArrayList<>(root.size());
        for (JsonNode entry : root) {
            if (entry == null || !entry.isObject() || !isInt(entry.get("id")) || entry.get("id").asInt() <= 0
                    || !isInt(entry.get("category")) || entry.get("category").asInt() < 0
                    || !isInt(entry.get("count")) || entry.get("count").asInt() < 0
                    || (entry.hasNonNull("binding") && !entry.get("binding").isTextual())) {
                throw new RuntimeException("Unexpected JSON (materials): malformed entry; nothing published");
            }
            MaterialStack row = MaterialParser.parse(entry);
            if (row == null) {
                throw new RuntimeException("Unexpected JSON (materials): malformed entry; nothing published");
            }
            rows.add(row);
        }
        return rows;
    }

    static List<Integer> parseAccountRecipeIds(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new RuntimeException("Unexpected JSON (account/recipes): not an array");
        }
        List<Integer> ids = new ArrayList<>(root.size());
        for (JsonNode idNode : root) {
            Integer id = isInt(idNode) ? RecipeIdParser.parse(idNode) : null;
            if (id == null || id <= 0) {
                throw new RuntimeException("Unexpected JSON (account/recipes): malformed id; nothing published");
            }
            ids.add(id);
        }
        return ids;
    }

    static String parseAccountId(JsonNode account) {
        if (account == null || !account.isObject() || !account.path("id").isTextual()
                || account.path("id").asText().isBlank()) {
            throw new IllegalArgumentException("Unexpected JSON (account): missing account id");
        }
        return account.path("id").asText();
    }

    static long parseConsumedLuck(JsonNode response) {
        if (response == null || !response.isArray() || response.size() > 1) {
            throw new IllegalArgumentException("Unexpected JSON (account/luck): expected zero or one entry");
        }
        if (response.isEmpty()) return 0;
        JsonNode item = response.get(0);
        if (item == null || !item.isObject() || !"luck".equals(item.path("id").asText())
                || !item.path("value").isIntegralNumber() || !item.path("value").canConvertToLong()
                || item.path("value").longValue() < 0) {
            throw new IllegalArgumentException("Unexpected JSON (account/luck): invalid luck value");
        }
        return item.path("value").longValue();
    }
}
