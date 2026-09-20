package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InventoryRepository {

    /**
     * "use mats from Bank" checked:
     * - include account_bank items + account_materials
     * unchecked:
     * - only account_materials
     *
     * Also includes item quantities held in character_items (all characters, all bag/equipment
     * locations) per DOMAIN_SPEC.md DQ-006 ("owned pool = material storage + bank + all character
     * inventories"). Binding (binding/bound_to) is intentionally not filtered here — every owned
     * character_items row counts toward the pool the same as bank/materials rows. Restricting
     * usable quantity by binding (DOMAIN_SPEC.md DQ-007) is a separate, not-yet-implemented rule
     * that needs a "selected character" concept that does not exist in the planner input yet.
     */
    public Map<Integer, Integer> loadOwnedInventory() throws SQLException {
        try (Connection con = repo.Db.open()) {
            return loadOwnedInventory(con);
        }
    }

    /**
     * Same query as {@link #loadOwnedInventory()}, but runs against a caller-supplied
     * connection instead of opening one via {@link repo.Db#open()}. Exists so repository
     * integration tests can point this query at a disposable test schema/database
     * (docs/TEST_STRATEGY.md §31.2) without going through production connection config.
     */
    public Map<Integer, Integer> loadOwnedInventory(Connection con) throws SQLException {
        Map<Integer, Integer> inv = new HashMap<>();

        // materials storage
        try (PreparedStatement ps = con.prepareStatement("""
            SELECT item_id, count
            FROM account_materials
            WHERE item_id IS NOT NULL
        """);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int itemId = rs.getInt("item_id");
                int count = rs.getInt("count");
                inv.merge(itemId, count, Integer::sum);
            }
        }

        // bank (always included if we're using owned mats)
        try (PreparedStatement ps = con.prepareStatement("""
            SELECT item_id, count
            FROM account_bank
            WHERE item_id IS NOT NULL
        """);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int itemId = rs.getInt("item_id");
                int count = rs.getInt("count");
                inv.merge(itemId, count, Integer::sum);
            }
        }

        // character inventories (all characters, bags + equipment; binding not filtered here)
        try (PreparedStatement ps = con.prepareStatement("""
            SELECT item_id, count
            FROM character_items
            WHERE item_id IS NOT NULL
        """);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int itemId = rs.getInt("item_id");
                int count = rs.getInt("count");
                inv.merge(itemId, count, Integer::sum);
            }
        }

        return inv;
    }

    /**
     * Owned quantity for {@code item_id}, split into an account-bound/soulbound-usable
     * portion ({@link OwnedQuantity#boundQty()}) and an ordinary unbound/tradable portion
     * ({@link OwnedQuantity#sellableQty()}), for the given selected character
     * (DOMAIN_SPEC.md section 11.1 / DQ-007, UD-001 — GW2 character name as identifier).
     * <p>
     * Per row {@code binding} (GW2 API convention: {@code "Account"} / {@code "Character"} /
     * absent):
     * <ul>
     *     <li>{@code binding} absent/null: ordinary owned quantity, counted as sellable
     *     exactly as {@link #loadOwnedInventory()} already does.</li>
     *     <li>{@code binding = "Account"}: usable by any character, counted as bound
     *     (no TP opportunity cost).</li>
     *     <li>{@code binding = "Character"}: usable only when {@code bound_to} matches
     *     {@code selectedCharacterName}, counted as bound; otherwise excluded entirely.</li>
     * </ul>
     * {@code account_materials} has no {@code bound_to} column, so its rows can only be
     * unbound or account-bound, never soulbound.
     * <p>
     * Additive: does not change {@link #loadOwnedInventory()}/{@link #loadOwnedInventory(Connection)}
     * or any call site that still uses them.
     */
    public Map<Integer, OwnedQuantity> loadOwnedInventoryForCharacter(String selectedCharacterName) throws SQLException {
        try (Connection con = repo.Db.open()) {
            return loadOwnedInventoryForCharacter(con, selectedCharacterName);
        }
    }

    /**
     * Same query as {@link #loadOwnedInventoryForCharacter(String)}, but runs against a
     * caller-supplied connection (see {@link #loadOwnedInventory(Connection)} for why).
     */
    public Map<Integer, OwnedQuantity> loadOwnedInventoryForCharacter(Connection con, String selectedCharacterName) throws SQLException {
        Map<Integer, Integer> sellable = new HashMap<>();
        Map<Integer, Integer> bound = new HashMap<>();

        // materials storage: binding is either absent or "Account" (never soulbound)
        try (PreparedStatement ps = con.prepareStatement("""
            SELECT item_id, count, binding
            FROM account_materials
            WHERE item_id IS NOT NULL
        """);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                classifyOwnedRow(rs.getInt("item_id"), rs.getInt("count"), rs.getString("binding"), null,
                        selectedCharacterName, sellable, bound);
            }
        }

        // bank
        try (PreparedStatement ps = con.prepareStatement("""
            SELECT item_id, count, binding, bound_to
            FROM account_bank
            WHERE item_id IS NOT NULL
        """);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                classifyOwnedRow(rs.getInt("item_id"), rs.getInt("count"), rs.getString("binding"), rs.getString("bound_to"),
                        selectedCharacterName, sellable, bound);
            }
        }

        // character inventories (all characters, bags + equipment)
        try (PreparedStatement ps = con.prepareStatement("""
            SELECT item_id, count, binding, bound_to
            FROM character_items
            WHERE item_id IS NOT NULL
        """);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                classifyOwnedRow(rs.getInt("item_id"), rs.getInt("count"), rs.getString("binding"), rs.getString("bound_to"),
                        selectedCharacterName, sellable, bound);
            }
        }

        Set<Integer> itemIds = new HashSet<>();
        itemIds.addAll(sellable.keySet());
        itemIds.addAll(bound.keySet());

        Map<Integer, OwnedQuantity> result = new HashMap<>();
        for (int itemId : itemIds) {
            result.put(itemId, new OwnedQuantity(sellable.getOrDefault(itemId, 0), bound.getOrDefault(itemId, 0)));
        }

        return result;
    }

    private static void classifyOwnedRow(int itemId, int count, String binding, String boundTo,
                                          String selectedCharacterName,
                                          Map<Integer, Integer> sellable, Map<Integer, Integer> bound) {
        if ("Character".equals(binding)) {
            if (selectedCharacterName != null && selectedCharacterName.equals(boundTo)) {
                bound.merge(itemId, count, Integer::sum);
            }
            // soulbound to a different character: excluded entirely, not counted as usable
            return;
        }

        if ("Account".equals(binding)) {
            bound.merge(itemId, count, Integer::sum);
            return;
        }

        sellable.merge(itemId, count, Integer::sum);
    }

    /**
     * Owned quantity for one item ID, split by binding usability for the selected character.
     *
     * @param sellableQty ordinary unbound/tradable owned quantity - priced/opportunity-costed
     *                    exactly as today.
     * @param boundQty    account-bound or soulbound-and-usable-by-the-selected-character owned
     *                    quantity - no normal TP opportunity cost (DOMAIN_SPEC.md section 11.1).
     */
    public record OwnedQuantity(int sellableQty, int boundQty) {
    }

    /**
     * Owned inventory for coordinated multi-character planning (DOMAIN_SPEC.md section 2.2.1 /
     * STORY-DOM-014): the shared account-wide ordinary/tradable pool, the shared account-wide
     * account-bound pool (usable by any character), and soulbound owned quantity split by owning
     * character - usable only by that exact character.
     */
    public record CoordinatedInventory(Map<Integer, Integer> sellable,
                                        Map<Integer, Integer> accountBound,
                                        Map<String, Map<Integer, Integer>> characterBound) {
    }

    /**
     * Same three-way split as {@link #loadOwnedInventoryForCharacter(String)}, but in one pass
     * for every character in {@code characterNames} at once, keyed by actual owner rather than a
     * single selected character. A soulbound row whose {@code bound_to} is not in {@code
     * characterNames} is excluded entirely, exactly as {@link #loadOwnedInventoryForCharacter}
     * excludes a row bound to any character other than the one selected - nobody in the
     * coordinated roster may use it.
     */
    public CoordinatedInventory loadOwnedInventoryForCharacters(Set<String> characterNames) throws SQLException {
        try (Connection con = repo.Db.open()) {
            return loadOwnedInventoryForCharacters(con, characterNames);
        }
    }

    /**
     * Same query as {@link #loadOwnedInventoryForCharacters(Set)}, but runs against a
     * caller-supplied connection (see {@link #loadOwnedInventory(Connection)} for why).
     */
    public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, Set<String> characterNames) throws SQLException {
        Map<Integer, Integer> sellable = new HashMap<>();
        Map<Integer, Integer> accountBound = new HashMap<>();
        Map<String, Map<Integer, Integer>> characterBound = new HashMap<>();

        // materials storage: binding is either absent or "Account" (never soulbound)
        try (PreparedStatement ps = con.prepareStatement("""
            SELECT item_id, count, binding
            FROM account_materials
            WHERE item_id IS NOT NULL
        """);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                classifyCoordinatedRow(rs.getInt("item_id"), rs.getInt("count"), rs.getString("binding"), null,
                        characterNames, sellable, accountBound, characterBound);
            }
        }

        // bank
        try (PreparedStatement ps = con.prepareStatement("""
            SELECT item_id, count, binding, bound_to
            FROM account_bank
            WHERE item_id IS NOT NULL
        """);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                classifyCoordinatedRow(rs.getInt("item_id"), rs.getInt("count"), rs.getString("binding"), rs.getString("bound_to"),
                        characterNames, sellable, accountBound, characterBound);
            }
        }

        // character inventories (all characters, bags + equipment)
        try (PreparedStatement ps = con.prepareStatement("""
            SELECT item_id, count, binding, bound_to
            FROM character_items
            WHERE item_id IS NOT NULL
        """);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                classifyCoordinatedRow(rs.getInt("item_id"), rs.getInt("count"), rs.getString("binding"), rs.getString("bound_to"),
                        characterNames, sellable, accountBound, characterBound);
            }
        }

        return new CoordinatedInventory(sellable, accountBound, characterBound);
    }

    private static void classifyCoordinatedRow(int itemId, int count, String binding, String boundTo,
                                                 Set<String> characterNames,
                                                 Map<Integer, Integer> sellable,
                                                 Map<Integer, Integer> accountBound,
                                                 Map<String, Map<Integer, Integer>> characterBound) {
        if ("Character".equals(binding)) {
            if (boundTo != null && characterNames.contains(boundTo)) {
                characterBound.computeIfAbsent(boundTo, k -> new HashMap<>())
                        .merge(itemId, count, Integer::sum);
            }
            // soulbound to a character outside the coordinated roster: excluded entirely, not
            // usable by anyone eligible in this plan.
            return;
        }

        if ("Account".equals(binding)) {
            accountBound.merge(itemId, count, Integer::sum);
            return;
        }

        sellable.merge(itemId, count, Integer::sum);
    }
}
