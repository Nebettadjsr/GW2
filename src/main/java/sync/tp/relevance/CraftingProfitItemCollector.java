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
 *  - unlocked on the account (account_recipes)
 *  - learned by at least one character (character_recipes)
 *
 * Optional tightening included:
 *  - character has discipline/rating to craft it (character_crafting vs recipes.disciplines/min_rating)
 *    (keep it enabled; it's correct and reduces noise)
 *
 * <p>The relevance definition itself lives in {@link #RELEVANT_TRADEABLE_ITEMS} and is shared by the
 * refresh selection ({@link #collect(Connection)}) and the coverage report
 * ({@link #coverage(Connection)}) so a calculation's reported price coverage can never describe a
 * different item set than the refresh would fetch (TARGET_ARCHITECTURE.md §9.1). Because relevance
 * is derived from account and character recipe knowledge, changed account inputs change the required
 * set, which is what keeps a result provisional until the newly required ids are covered.
 */
public final class CraftingProfitItemCollector {

    /**
     * Every Profit-relevant, Trading-Post-tradeable item id with the stored quote's fetch instant
     * and its four quoted numbers ({@code NULL} throughout when no quote row exists at all).
     * {@code tp_prices} is keyed on {@code item_id}, so this yields exactly one row per relevant
     * tradeable item.
     *
     * <p>The quoted numbers are selected because {@link #coverage(Connection)} fingerprints them:
     * the aggregate counts and instants cannot distinguish one interior quote changing from nothing
     * changing, and a calculation that read a different quote is a different calculation.
     */
    static final String RELEVANT_TRADEABLE_ITEMS = """
WITH craftable_recipes AS (
  SELECT recipe_id FROM account_recipes
  UNION
  SELECT DISTINCT recipe_id FROM character_recipes
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

    private static final String SQL = RELEVANT_TRADEABLE_ITEMS + """
SELECT item_id FROM relevant_tradeable tp
WHERE
""" + TpPriceFreshness.STALE_SQL_PREDICATE;

    /**
     * The coverage report, including {@code quote_version}: one digest over every relevant item's
     * own quote — its id, its fetch instant and its four quoted numbers, ordered by id, with each
     * absent value written as a distinct marker so a missing quote cannot collide with a zero one.
     * The instant is rendered in UTC so two sessions with different {@code TimeZone} settings
     * fingerprint identical data identically. {@code string_agg} over no rows is {@code NULL}, which
     * is the empty required set's version.
     */
    private static final String COVERAGE_SQL = RELEVANT_TRADEABLE_ITEMS + """
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
     *                     so this is what makes a changed input a changed calculation identity
     *                     (TARGET_ARCHITECTURE.md §9.1).
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

    public Set<Integer> collect(Connection con) throws Exception {
        Set<Integer> itemIds = new HashSet<>();

        try (PreparedStatement ps = con.prepareStatement(SQL);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                itemIds.add(rs.getInt(1));
            }
        }

        return itemIds;
    }

    public Coverage coverage(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COVERAGE_SQL);
             ResultSet rs = ps.executeQuery()) {

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
