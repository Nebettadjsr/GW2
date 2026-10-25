package sync.tp.relevance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import sync.tp.TpPriceFreshness;

public final class DiscoveryItemCollector {

    private static final String SQL_TEMPLATE = """
          WITH excluded AS (
          %1$s
          ),
          discoverable AS (
            SELECT r.recipe_id
            FROM recipes r
            LEFT JOIN excluded e ON e.recipe_id = r.recipe_id
            WHERE e.recipe_id IS NULL
          ),
          relevant_items AS (
            SELECT r.output_item_id AS item_id
            FROM recipes r
            JOIN discoverable d ON d.recipe_id = r.recipe_id
            WHERE r.output_item_id IS NOT NULL
          
            UNION
          
            SELECT ri.item_id
            FROM recipe_ingredients ri
            JOIN discoverable d ON d.recipe_id = ri.recipe_id
            WHERE ri.item_id IS NOT NULL
          )
          SELECT DISTINCT i.item_id
        FROM relevant_items i
        JOIN tp_tradeable_items t ON t.item_id = i.item_id
        LEFT JOIN tp_prices tp ON tp.item_id = i.item_id
        WHERE
        """ + TpPriceFreshness.STALE_SQL_PREDICATE;

    /** Unlocks across every account, the exclusion set the unscoped definition reads. */
    private static final String ALL_ACCOUNTS_UNLOCKS = """
            SELECT recipe_id FROM account_recipes
            UNION
            SELECT DISTINCT recipe_id FROM character_recipes""";

    /** One account's unlocks; two bind parameters, both that account's GUID. */
    private static final String SCOPED_UNLOCKS = """
            SELECT recipe_id FROM account_recipes WHERE account_id = ?
            UNION
            SELECT DISTINCT recipe_id FROM character_recipes WHERE account_id = ?""";

    /**
     * Nothing excluded (STORY-SYNC-007): with no account in play there is no unlock to subtract, so
     * every recipe in the shared graph is a Discovery candidate. Reads no account table, so this
     * variant needs no scope - and an empty account registry does not make it look like an account
     * that happens to know nothing.
     */
    private static final String NO_UNLOCKS = "    SELECT NULL::integer AS recipe_id WHERE false";

    private static final String SQL = SQL_TEMPLATE.formatted(ALL_ACCOUNTS_UNLOCKS);

    /**
     * The same definition for one account (STORY-APP-015). "Not yet unlocked" is an account-owned
     * fact, so the exclusion set must be this account's unlocks: another account's knowledge may
     * neither hide a Discovery candidate from this one nor reveal one.
     *
     * <p>Two bind parameters, both the account GUID, in the order the two unlock sources appear.
     */
    private static final String SCOPED_SQL = SQL_TEMPLATE.formatted(SCOPED_UNLOCKS);

    /** @see #NO_UNLOCKS */
    private static final String GLOBAL_SQL = SQL_TEMPLATE.formatted(NO_UNLOCKS);

    public Set<Integer> collect(Connection con) throws Exception {
        // "Not yet unlocked" is an account-owned fact on a migrated database (STORY-INFRA-004).
        repo.ManagedSchema.rejectUnscopedAccountAccess(con, "Discovery price relevance without an account scope");
        Set<Integer> itemIds = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) itemIds.add(rs.getInt(1));
        }
        return itemIds;
    }

    /**
     * The Discovery-relevant item ids whose stored quote is stale or absent, over shared data only
     * (STORY-SYNC-007): the global half of the split, so the Discovery price refresh runs on a
     * migrated, empty-account database without a verified credential.
     *
     * <p>The ten-minute freshness policy and the required-ID-only filter are the shared ones, so
     * this selection and any account-derived one read and refresh the same quote cache.
     */
    public Set<Integer> collectGlobal(Connection con) throws Exception {
        Set<Integer> itemIds = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(GLOBAL_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) itemIds.add(rs.getInt(1));
        }
        return itemIds;
    }

    /** @see #SCOPED_SQL */
    public Set<Integer> collect(Connection con, model.AccountScope scope) throws Exception {
        repo.ManagedSchema.requireManagedAccountSchema(con, "Discovery price relevance");
        Set<Integer> itemIds = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(SCOPED_SQL)) {
            ps.setObject(1, scope.accountId());
            ps.setObject(2, scope.accountId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }
        }
        return itemIds;
    }
}
