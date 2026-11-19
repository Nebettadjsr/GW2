package sync.tp.relevance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import sync.tp.TpPriceFreshness;

/**
 * Collects TP-relevant item ids for CraftingProfitView:
 * only items that belong to recipes that are:
 *  - AutoLearned, or unlocked on the account (account_recipes)
 *  - learned by at least one character (character_recipes)
 *
 * Optional tightening included:
 *  - character has discipline/rating to craft it (character_crafting vs recipes.disciplines/min_rating)
 *    (keep it enabled; it's correct and reduces noise)
 *
 * <p>The relevance definition itself lives in {@link #RELEVANT_TRADEABLE_ITEMS_TEMPLATE} and is shared by the
 * refresh selection ({@link #collect(Connection, model.AccountScope)}) and the coverage report
 * ({@link #coverage(Connection, model.AccountScope)}) so a calculation's reported price coverage can never describe a
 * different item set than the refresh would fetch. Because relevance
 * is derived from account and character recipe knowledge, changed account inputs change the required
 * set, which is what keeps a result provisional until the newly required ids are covered.
 *
 * <p>The definition exists in two variants that differ only in which recipes
 * count as craftable: one account's knowledge, and the shared crafting graph itself for the global
 * selection that needs no account at all. The item derivation, the tradeability join, the shared
 * quote cache and the ten-minute freshness policy are the same in both.
 */
public final class CraftingProfitItemCollector {

    /**
     * Every Profit-relevant, Trading-Post-tradeable item id with the stored quote's fetch instant
     * and its four quoted numbers ({@code NULL} throughout when no quote row exists at all).
     * {@code tp_prices} is keyed on {@code item_id}, so this yields exactly one row per relevant
     * tradeable item.
     *
     * <p>The quoted numbers are selected because {@link #coverage(Connection, model.AccountScope)} fingerprints them:
     * the aggregate counts and instants cannot distinguish one interior quote changing from nothing
     * changing, and a calculation that read a different quote is a different calculation.
     */
    private static final String RELEVANT_TRADEABLE_ITEMS_TEMPLATE = """
WITH craftable_recipes AS (
%1$s
),
relevant_items AS (
  SELECT r.output_item_id AS item_id
  FROM recipes r
  JOIN craftable_recipes c ON c.recipe_id = r.recipe_id
  WHERE r.output_item_id IS NOT NULL

  UNION

  SELECT ri.item_id
  FROM recipe_ingredients ri
  JOIN craftable_recipes c ON c.recipe_id = ri.recipe_id
  WHERE ri.item_id IS NOT NULL
),
relevant_tradeable AS (
  SELECT DISTINCT i.item_id, tp.fetched_at,
         tp.buy_quantity, tp.buy_unit_price, tp.sell_quantity, tp.sell_unit_price
  FROM relevant_items i
  JOIN tp_tradeable_items t ON t.item_id = i.item_id
  LEFT JOIN tp_prices tp ON tp.item_id = i.item_id
)
""";

    /**
     * One account's recipe knowledge: its account and character unlocks plus AutoLearned recipes, which have no
     * unlock rows (same predicate as {@code RecipeRepository}). Two bind parameters, both that account's GUID.
     */
    private static final String SCOPED_RECIPE_KNOWLEDGE = """
  SELECT recipe_id FROM account_recipes WHERE account_id = ?
  UNION
  SELECT DISTINCT recipe_id FROM character_recipes WHERE account_id = ?
  UNION
  SELECT recipe_id FROM recipes WHERE 'AutoLearned' = ANY(flags)""";

    /**
     * The shared crafting graph itself, which is what "craftable" means with no
     * account in play: {@code recipes}/{@code recipe_ingredients} are global reference data and
     * account recipe knowledge never partitions them (V2 migration comment on
     * {@code account_recipes}), so this reads no account table and needs no scope.
     */
    private static final String EVERY_SHARED_RECIPE = "  SELECT recipe_id FROM recipes";

    /**
     * The same definition for one account: only this account's recipe knowledge
     * decides which items are relevant, so one account's unlocks can neither widen nor narrow
     * another's required item set - while the shared quote cache the set is measured against stays
     * global and is reused by every account.
     *
     * <p>Two bind parameters, both the account GUID, in the order the two unlock sources appear.
     */
    static final String SCOPED_RELEVANT_TRADEABLE_ITEMS =
            RELEVANT_TRADEABLE_ITEMS_TEMPLATE.formatted(SCOPED_RECIPE_KNOWLEDGE);

    /**
     * The same definition over shared data only: no account row enters it, so the
     * selection is identical for every caller and for no caller at all, and an empty account
     * registry neither empties it nor makes it infer an account.
     */
    static final String GLOBAL_RELEVANT_TRADEABLE_ITEMS =
            RELEVANT_TRADEABLE_ITEMS_TEMPLATE.formatted(EVERY_SHARED_RECIPE);

    private static final String SELECT_STALE = """
SELECT item_id FROM relevant_tradeable tp
WHERE
""" + TpPriceFreshness.STALE_SQL_PREDICATE;

    private static final String SCOPED_SQL = SCOPED_RELEVANT_TRADEABLE_ITEMS + SELECT_STALE;

    private static final String GLOBAL_SQL = GLOBAL_RELEVANT_TRADEABLE_ITEMS + SELECT_STALE;

    /**
     * The coverage report, including {@code quote_version}: one digest over every relevant item's
     * own quote — its id, its fetch instant and its four quoted numbers, ordered by id, with each
     * absent value written as a distinct marker so a missing quote cannot collide with a zero one.
     * The instant is rendered in UTC so two sessions with different {@code TimeZone} settings
     * fingerprint identical data identically. {@code string_agg} over no rows is {@code NULL}, which
     * is the empty required set's version.
     */
    private static final String COVERAGE_SELECT = """
SELECT COUNT(*) AS required,
       COUNT(*) FILTER (WHERE fetched_at IS NOT NULL
                          AND fetched_at >= now() - interval '%d minutes') AS fresh,
       COUNT(*) FILTER (WHERE fetched_at IS NOT NULL
                          AND fetched_at <  now() - interval '%d minutes') AS stale,
       COUNT(*) FILTER (WHERE fetched_at IS NULL) AS missing,
       MIN(fetched_at) AS oldest,
       MAX(fetched_at) AS newest,
       md5(string_agg(
             item_id::text
               || ':' || COALESCE(to_char(fetched_at AT TIME ZONE 'UTC',
                                          'YYYY-MM-DD HH24:MI:SS.US'), '-')
               || ':' || COALESCE(buy_quantity::text, '-')
               || ':' || COALESCE(buy_unit_price::text, '-')
               || ':' || COALESCE(sell_quantity::text, '-')
               || ':' || COALESCE(sell_unit_price::text, '-'),
             ',' ORDER BY item_id)) AS quote_version
FROM relevant_tradeable
""".formatted(TpPriceFreshness.TTL.toMinutes(), TpPriceFreshness.TTL.toMinutes());

    private static final String SCOPED_COVERAGE_SQL = SCOPED_RELEVANT_TRADEABLE_ITEMS + COVERAGE_SELECT;

    /**
     * How much of the Profit-relevant tradeable item set the stored quote cache currently answers.
     *
     * <p>{@code missing} counts relevant items with no stored quote row at all. An absent row is
     * never reported as a fresh authoritative "no market" answer; only a successful fetch writes the
     * explicit all-null row that makes an item covered (see {@code sync.TpSync}).
     *
     * @param quoteVersion digest of the relevant quotes themselves, null when nothing is relevant.
     *                     The counts and the two instants are aggregates and therefore blind to an
     *                     interior change — one item refetched between the oldest and the newest
     *                     instant, or a quote rewritten with new numbers under the same instant —
     *                     so this is what makes a changed input a changed calculation identity.
     */
    public record Coverage(int requiredItems,
                           int freshItems,
                           int staleItems,
                           int missingItems,
                           Instant oldestFetchedAt,
                           Instant newestFetchedAt,
                           String quoteVersion) {

        /** True when every relevant item has a quote fetched inside the shared ten-minute policy. */
        public boolean covered() {
            return staleItems == 0 && missingItems == 0;
        }

        public int uncoveredItems() {
            return staleItems + missingItems;
        }

        /** Nothing relevant is known yet, so no coverage claim can be made from stored quotes. */
        public static Coverage unknown() {
            return new Coverage(0, 0, 0, 0, null, null, null);
        }
    }

    /**
     * The Profit-relevant item ids whose stored quote is stale or absent, over shared data only.
     *
     * <p>The global half of the split: it reads the shared crafting graph and the shared quote
     * cache, so the Trading Post price refresh runs on a migrated, empty-account database with no
     * verified credential and without inferring one. Only the required set differs from
     * {@link #collect(Connection, model.AccountScope)} - the ten-minute freshness policy and the
     * required-ID-only filter are the same {@code SELECT_STALE}, so a quote this selection already
     * refreshed is reused by every account demand instead of being refetched per account.
     */
    public Set<Integer> collectGlobal(Connection con) throws Exception {
        Set<Integer> itemIds = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(GLOBAL_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) itemIds.add(rs.getInt(1));
        }
        return itemIds;
    }

    /**
     * One account's Profit-relevant item ids whose stored quote is stale or absent.
     *
     * <p>The scoped counterpart of {@link #collectGlobal(Connection)}: the relevance definition is the same
     * template, so the set a refresh fetches for this account and the set
     * {@link #coverage(Connection, model.AccountScope)} measures still cannot diverge.
     */
    public Set<Integer> collect(Connection con, model.AccountScope scope) throws Exception {
        repo.ManagedSchema.requireManagedAccountSchema(con, "Profit price relevance");
        Set<Integer> itemIds = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(SCOPED_SQL)) {
            bindScope(ps, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }
        }
        return itemIds;
    }

    /**
     * How much of <em>this account's</em> Profit-relevant item set the shared quote cache answers.
     *
     * <p>Only the required set is account-derived. The quotes themselves, their freshness policy and
     * the {@code quote_version} digest are global, so two accounts requiring the same item read the
     * same quote and the same version - which is what keeps the shared cache reusable across
     * accounts instead of being refetched per account.
     */
    public Coverage coverage(Connection con, model.AccountScope scope) throws SQLException {
        repo.ManagedSchema.requireManagedAccountSchema(con, "Profit price coverage");
        try (PreparedStatement ps = con.prepareStatement(SCOPED_COVERAGE_SQL)) {
            bindScope(ps, scope);
            return coverageOf(ps);
        }
    }

    private static void bindScope(PreparedStatement ps, model.AccountScope scope) throws SQLException {
        ps.setObject(1, scope.accountId());
        ps.setObject(2, scope.accountId());
    }

    private static Coverage coverageOf(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Coverage.unknown();
            return new Coverage(
                    rs.getInt("required"),
                    rs.getInt("fresh"),
                    rs.getInt("stale"),
                    rs.getInt("missing"),
                    instant(rs.getTimestamp("oldest")),
                    instant(rs.getTimestamp("newest")),
                    rs.getString("quote_version"));
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
