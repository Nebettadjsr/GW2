package sync;

import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import model.BankSlot;
import model.MaterialStack;
import model.AccountLuck;
import parser.BankParser;
import parser.MaterialParser;
import parser.RecipeIdParser;
import repo.Db;
import repo.MaterialStorageSchema;
import repo.AccountLuckRepository;
import repo.AccountLuckSchema;
import repo.AccountSyncStateRepository;
import util.DbBind;

import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

public final class AccountSync {
    private AccountSync() {}

    private static repo.AccountSourcePublication publication(java.time.Clock clock) {
        return new repo.AccountSourcePublication(clock);
    }

    /**
     * Fetches and publishes one account's bank (STORY-APP-015).
     *
     * <p>The upstream call and the parse happen first and outside any transaction, so a slow or
     * failing GW2 API never holds a database transaction open
     * (TARGET_ARCHITECTURE.md §7.2). Only once the whole payload is in hand does
     * {@link repo.AccountSourcePublication} open the transaction that replaces the rows, removes the
     * ones this run did not refresh, advances the {@code BANK} generation and records the account's
     * successful-sync activity - all or nothing.
     */
    public static void syncAccountBank(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        syncAccountBankImpl(scope, fetcher);
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

    static void syncAccountBankImpl(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        syncAccountBankImpl(scope, fetcher, ids -> {});
    }

    static void syncAccountBankImpl(model.AccountScope scope, AuthFetcher fetcher, IconSync.ItemMetadataRepair metadata)
            throws Exception {
        requireCredentialOwner(scope, fetcher);
        List<BankSlot> rows = parseBankSlots(fetcher.get("https://api.guildwars2.com/v2/account/bank"));
        metadata.repair(rows.stream().map(BankSlot::itemId).toList());
        try (Connection con = Db.open()) {
            publishBank(con, scope, rows, java.time.Clock.systemUTC());
        }
    }

    /** Publishes already-fetched bank slots; see {@link #syncAccountBank(model.AccountScope)}. */
    static repo.AccountSourcePublication.Published publishBank(Connection con,
                                                               model.AccountScope scope,
                                                               List<BankSlot> rows,
                                                               java.time.Clock clock) throws Exception {
        return publication(clock).publish(con, scope, AccountSyncStateRepository.BANK,
                (tx, owner, runTs) -> upsertScopedAccountBank(tx, owner, rows, runTs));
    }

    /** Fetches and publishes one account's material storage; see {@link #syncAccountBank(model.AccountScope)}. */
    public static void syncAccountMaterials(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        syncAccountMaterialsImpl(scope, fetcher);
    }

    static void syncAccountMaterialsImpl(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
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
     * in {@code account_source_state} (TARGET_ARCHITECTURE.md §7.2).
     */
    static repo.AccountSourcePublication.Published publishMaterials(Connection con,
                                                                    model.AccountScope scope,
                                                                    List<MaterialStack> rows,
                                                                    java.time.Clock clock) throws Exception {
        return publication(clock).publish(con, scope, AccountSyncStateRepository.MATERIALS,
                (tx, owner, runTs) -> upsertScopedAccountMaterials(tx, owner, rows, runTs));
    }

    /** Fetches and publishes one account's recipe unlocks; see {@link #syncAccountBank(model.AccountScope)}. */
    public static void syncAccountRecipes(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        syncAccountRecipesImpl(scope, fetcher);
    }

    static void syncAccountRecipesImpl(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        requireCredentialOwner(scope, fetcher);
        List<Integer> ids = parseAccountRecipeIds(fetcher.get("https://api.guildwars2.com/v2/account/recipes"));
        try (Connection con = Db.open()) {
            publishRecipes(con, scope, ids, java.time.Clock.systemUTC());
        }
    }

    /** Publishes already-fetched account recipe ids; see {@link #syncAccountBank(model.AccountScope)}. */
    static repo.AccountSourcePublication.Published publishRecipes(Connection con,
                                                                  model.AccountScope scope,
                                                                  List<Integer> ids,
                                                                  java.time.Clock clock) throws Exception {
        return publication(clock).publish(con, scope, AccountSyncStateRepository.RECIPES,
                (tx, owner, runTs) -> upsertScopedAccountRecipes(tx, owner, ids, runTs));
    }

    /**
     * Fetches and publishes one account's consumed Luck; see
     * {@link #syncAccountBank(model.AccountScope)}.
     *
     * <p>The upstream {@code /v2/account} identifier is validated against {@code scope} before
     * anything is written: a key that resolves to a different account must not write under the
     * caller's GUID, and the publication is refused rather than silently rescoped.
     */
    public static void syncAccountLuck(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        syncAccountLuckImpl(scope, fetcher);
    }

    static void syncAccountLuckImpl(model.AccountScope scope, AuthFetcher fetcher) throws Exception {
        requireCredentialOwner(scope, fetcher);
        long consumedLuck = parseConsumedLuck(fetcher.get("https://api.guildwars2.com/v2/account/luck"));
        try (Connection con = Db.open()) {
            publishLuck(con, scope, consumedLuck, java.time.Clock.systemUTC());
        }
    }

    /** Publishes already-fetched consumed Luck; see {@link #syncAccountLuck(model.AccountScope)}. */
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

    private static List<BankSlot> fetchBankSlots() throws Exception {
        return parseBankSlots(Gw2ApiClient.getAuth("https://api.guildwars2.com/v2/account/bank"));
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

    private static List<MaterialStack> fetchMaterialStacks() throws Exception {
        return parseMaterialStacks(Gw2ApiClient.getAuth("https://api.guildwars2.com/v2/account/materials"));
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

    private static List<Integer> fetchAccountRecipeIds() throws Exception {
        return parseAccountRecipeIds(Gw2ApiClient.getAuth("https://api.guildwars2.com/v2/account/recipes"));
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

    /**
     * Fetches consumed Luck for the API key's stable account ID. The GW2 endpoint requires
     * account, progression and unlocks permissions; getAuth applies the same status/error policy
     * as the existing account sync steps. An empty Luck array means zero, not unavailable data.
     */
    public static void syncAccountLuck() throws Exception {
        JsonNode account = Gw2ApiClient.getAuth("https://api.guildwars2.com/v2/account");
        String accountId = parseAccountId(account);
        JsonNode response = Gw2ApiClient.getAuth("https://api.guildwars2.com/v2/account/luck");
        long consumedLuck = parseConsumedLuck(response);
        try (Connection con = Db.open()) {
            AccountLuckSchema.ensure(con);
            con.setAutoCommit(false);
            try {
                Instant fetchedAt = Instant.now();
                new AccountLuckRepository().save(con, new AccountLuck(accountId, consumedLuck, fetchedAt));
                AccountSyncStateRepository.mark(con, AccountSyncStateRepository.LUCK, fetchedAt);
                con.commit();
            } catch (Exception e) { con.rollback(); throw e; }
        }
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

        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try {
                AccountSyncStateRepository.ensure(con);
                Timestamp runTs;
                try (PreparedStatement psNow = con.prepareStatement("SELECT now()");
                     ResultSet rs = psNow.executeQuery()) {
                    if (!rs.next()) throw new SQLException("SELECT now() returned no row");
                    runTs = rs.getTimestamp(1);
                }

                upsertAccountBank(con, rows, runTs);
                AccountSyncStateRepository.mark(con, AccountSyncStateRepository.BANK, runTs.toInstant());

                con.commit();
            } catch (Exception ex) {
                con.rollback();
                throw ex;
            }
        }
    }

    /**
     * Upserts already-fetched bank slots into {@code account_bank} and deletes rows not refreshed
     * by this run ({@code fetched_at < runTs}). Extracted from {@link #syncAccountBank} so the
     * write path is callable independently of the live GW2 fetch in tests.
     */
    static void upsertAccountBank(Connection con, List<BankSlot> rows, Timestamp runTs) throws SQLException {
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

        try (Connection con = Db.open()) {
            // Unlike the bank and account-recipe publications below, this one's first write is the
            // unscoped account_materials upsert rather than a source marker, so the migrated-schema
            // refusal is stated here explicitly (STORY-INFRA-004).
            repo.ManagedSchema.rejectUnscopedAccountAccess(con, "account_materials without an account scope");
            MaterialStorageSchema.ensure(con);
            con.setAutoCommit(false);
            try {
                Timestamp runTs;
                try (PreparedStatement psNow = con.prepareStatement("SELECT now()");
                     ResultSet rs = psNow.executeQuery()) {
                    if (!rs.next()) throw new SQLException("SELECT now() returned no row");
                    runTs = rs.getTimestamp(1);
                }

                upsertAccountMaterials(con, rows, runTs);
                try (PreparedStatement marker = con.prepareStatement("""
                        INSERT INTO account_materials_sync (id, fetched_at) VALUES (1, ?)
                        ON CONFLICT (id) DO UPDATE SET fetched_at = EXCLUDED.fetched_at
                        """)) {
                    marker.setTimestamp(1, runTs);
                    marker.executeUpdate();
                }
                AccountSyncStateRepository.mark(con, AccountSyncStateRepository.MATERIALS, runTs.toInstant());

                con.commit();
            } catch (Exception ex) {
                con.rollback();
                throw ex;
            }
        }
    }

    /**
     * Upserts already-fetched material stacks into {@code account_materials} and deletes rows not
     * refreshed by this run ({@code fetched_at < runTs}). Extracted from
     * {@link #syncAccountMaterials} so the write path is callable independently of the live GW2
     * fetch in tests.
     */
    static void upsertAccountMaterials(Connection con, List<MaterialStack> rows, Timestamp runTs) throws SQLException {
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

        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try {
                AccountSyncStateRepository.ensure(con);
                Timestamp runTs;
                try (PreparedStatement psNow = con.prepareStatement("SELECT now()");
                     ResultSet rs = psNow.executeQuery()) {
                    if (!rs.next()) throw new SQLException("SELECT now() returned no row");
                    runTs = rs.getTimestamp(1);
                }

                upsertAccountRecipes(con, ids, runTs);
                AccountSyncStateRepository.mark(con, AccountSyncStateRepository.RECIPES, runTs.toInstant());

                con.commit();
            } catch (Exception ex) {
                con.rollback();
                throw ex;
            }
        }
    }

    /**
     * Upserts already-fetched recipe IDs into {@code account_recipes} and deletes rows not
     * refreshed by this run ({@code fetched_at < runTs}). Extracted from {@link #syncAccountRecipes}
     * so the write path is callable independently of the live GW2 fetch in tests.
     */
    static void upsertAccountRecipes(Connection con, List<Integer> ids, Timestamp runTs) throws SQLException {
        String upsertSql = """
            INSERT INTO account_recipes (recipe_id, fetched_at)
            VALUES (?, ?)
            ON CONFLICT (recipe_id) DO UPDATE SET fetched_at = EXCLUDED.fetched_at
            """;

        String deleteStaleSql = "DELETE FROM account_recipes WHERE fetched_at < ?";

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
    }
}
