package repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InventoryRepository {

    /**
     * The three account-owned pools the owned quantity is summed from, each scoped by its account
     * GUID. {@code account_materials} has no {@code bound_to} column, so its rows
     * can only be unbound or account-bound, never soulbound - the {@code NULL} literal keeps the
     * three result shapes identical so one row loop can classify all of them.
     */
    private static final String[] SCOPED_POOL_QUERIES = {
            """
            SELECT item_id, count, binding, NULL AS bound_to
            FROM account_materials
            WHERE account_id = ? AND item_id IS NOT NULL
            """,
            """
            SELECT slot, item_id, count, binding, bound_to
            FROM account_bank
            WHERE account_id = ? AND item_id IS NOT NULL
            ORDER BY slot
            """,
            """
            SELECT item_id, count, binding, bound_to
            FROM character_items
            WHERE account_id = ? AND item_id IS NOT NULL
            """
    };

    /**
     * One account's total owned quantity per item, the scoped form of
     * {@link #loadOwnedInventory(Connection)}.
     *
     * <p>Same pool definition as the unscoped version - material storage plus bank plus every
     * character's bags and equipment (DOMAIN_SPEC.md DQ-006), binding deliberately not filtered -
     * with the owning account added to each of the three reads. Summing them without that scope is
     * what would mix two accounts' quantities into one number.
     */
    public Map<Integer, Integer> loadOwnedInventory(Connection con, model.AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "owned inventory");
        Map<Integer, Integer> inv = new HashMap<>();
        forEachScopedRow(con, scope, (itemId, count, binding, boundTo) -> inv.merge(itemId, count, Integer::sum));
        return inv;
    }

    /**
     * One account's owned quantity split by binding usability for {@code selectedCharacterName}, the scoped form of
     * {@link #loadOwnedInventoryForCharacter(Connection, String)}.
     *
     * <p>The binding rules themselves are unchanged and stay in {@link #classifyOwnedRow}: scoping
     * decides which rows are read, not how they are classified. A character name only has meaning
     * within its account, which is why the selected character must be resolved inside the same
     * scope.
     */
    public Map<Integer, OwnedQuantity> loadOwnedInventoryForCharacter(model.AccountScope scope,
                                                                      String selectedCharacterName)
            throws SQLException {
        try (Connection con = repo.Db.open()) {
            return loadOwnedInventoryForCharacter(con, scope, selectedCharacterName);
        }
    }

    /** @see #loadOwnedInventoryForCharacter(model.AccountScope, String) */
    public Map<Integer, OwnedQuantity> loadOwnedInventoryForCharacter(Connection con,
                                                                      model.AccountScope scope,
                                                                      String selectedCharacterName)
            throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "owned inventory");
        Map<Integer, Integer> sellable = new HashMap<>();
        Map<Integer, Integer> bound = new HashMap<>();
        forEachScopedRow(con, scope, (itemId, count, binding, boundTo) ->
                classifyOwnedRow(itemId, count, binding, boundTo, selectedCharacterName, sellable, bound));
        return combine(sellable, bound);
    }

    /**
     * One account's three-way coordinated inventory split, the scoped form of
     * {@link #loadOwnedInventoryForCharacters(Connection, Set)}.
     */
    public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con,
                                                                model.AccountScope scope,
                                                                Set<String> characterNames)
            throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "owned inventory");
        Map<Integer, Integer> sellable = new HashMap<>();
        Map<Integer, Integer> accountBound = new HashMap<>();
        Map<String, Map<Integer, Integer>> characterBound = new HashMap<>();
        forEachScopedRow(con, scope, (itemId, count, binding, boundTo) ->
                classifyCoordinatedRow(itemId, count, binding, boundTo, characterNames,
                        sellable, accountBound, characterBound));
        return new CoordinatedInventory(sellable, accountBound, characterBound);
    }

    /** One owned row as every pool reports it. */
    private interface OwnedRowHandler {
        void accept(int itemId, int count, String binding, String boundTo);
    }

    private static void forEachScopedRow(Connection con, model.AccountScope scope, OwnedRowHandler handler)
            throws SQLException {
        forEachScopedRow(con, scope, Set.of(), Set.of(), handler);
    }

    private static void forEachScopedRow(Connection con, model.AccountScope scope,
                                         Set<Integer> excludedBankIds, Set<Integer> excludedMaterialIds,
                                         OwnedRowHandler handler) throws SQLException {
        forEachScopedRow(con, scope, excludedBankIds, excludedMaterialIds, Map.of(), Map.of(), List.of(), handler);
    }

    private record Row(int slot, int itemId, int count, String binding, String boundTo) { }

    private static void forEachScopedRow(Connection con, model.AccountScope scope,
                                         Set<Integer> excludedBankIds, Set<Integer> excludedMaterialIds,
                                         Map<Integer, Integer> bankQuantities, Map<Integer, Integer> materialQuantities,
                                         List<craft.BankAllocation> bankAllocations,
                                         OwnedRowHandler handler) throws SQLException {
        for (int pool = 0; pool < SCOPED_POOL_QUERIES.length; pool++) {
            String sql = SCOPED_POOL_QUERIES[pool];
            if (scope == null) sql = sql.replace("account_id = ? AND ", "");
            Set<Integer> excluded = pool == 0 ? excludedMaterialIds : pool == 1 ? excludedBankIds : Set.of();
            Map<Integer, Integer> target = pool == 0 ? materialQuantities : pool == 1 ? bankQuantities : Map.of();
            if (target == null || target.isEmpty()) {
                // No finite target for this pool: keep the original streaming path, no buffering.
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    if (scope != null) ps.setObject(1, scope.accountId());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int itemId = rs.getInt("item_id");
                            if (excluded.contains(itemId)) continue;
                            int count = rs.getInt("count");
                            if (count > 0) handler.accept(itemId, count, rs.getString("binding"), rs.getString("bound_to"));
                        }
                    }
                }
                continue;
            }
            // Homogeneous finite allocation needs every stack of a targeted item, so only this pool is buffered.
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                if (scope != null) ps.setObject(1, scope.accountId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (excluded.contains(rs.getInt("item_id"))) continue;
                        rows.add(new Row(pool == 1 ? rs.getInt("slot") : 0, rs.getInt("item_id"), rs.getInt("count"),
                                rs.getString("binding"), rs.getString("bound_to")));
                    }
                }
            }
            Map<Integer, Long> remaining = pool == 1 ? Map.of() : reservationsToApply(rows, target);
            Map<Integer, Integer> bankKept = pool == 1 ? bankReservedBySlot(rows, target, bankAllocations) : Map.of();
            for (Row r : rows) {
                int count = r.count();
                if (pool == 1) {
                    count -= bankKept.getOrDefault(r.slot(), 0);
                } else {
                    Long keep = remaining.get(r.itemId());
                    if (keep != null && keep > 0) {
                        int taken = (int) Math.min(keep, count);
                        count -= taken;
                        remaining.put(r.itemId(), keep - taken);
                    }
                }
                if (count > 0) handler.accept(r.itemId(), count, r.binding(), r.boundTo());
            }
        }
    }

    /**
     * Units reserved per current Bank slot. A complete, matching manual layout (same slots, item,
     * count, binding and owner) keeps the selected rows; any difference redistributes the same fixed
     * target over the item's current positive-count rows in ascending slot order. Never throws.
     */
    private static Map<Integer, Integer> bankReservedBySlot(List<Row> rows, Map<Integer, Integer> target,
                                                            List<craft.BankAllocation> hints) {
        Map<Integer, List<Row>> byItem = new HashMap<>();
        for (Row r : rows) {
            if (r.count() > 0 && target.containsKey(r.itemId())) {
                byItem.computeIfAbsent(r.itemId(), k -> new ArrayList<>()).add(r);
            }
        }
        Map<Integer, List<craft.BankAllocation>> hintsByItem = new HashMap<>();
        if (hints != null) for (craft.BankAllocation h : hints) {
            hintsByItem.computeIfAbsent(h.itemId(), k -> new ArrayList<>()).add(h);
        }
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<Integer, List<Row>> e : byItem.entrySet()) {
            List<Row> current = e.getValue();
            current.sort(java.util.Comparator.comparingInt(Row::slot));
            if (hintsMatch(current, hintsByItem.get(e.getKey()))) {
                for (craft.BankAllocation h : hintsByItem.get(e.getKey())) {
                    if (h.reservedUnits() > 0) out.put(h.slot(), h.reservedUnits());
                }
                continue;
            }
            long remaining = target.get(e.getKey());
            for (Row r : current) {
                if (remaining <= 0) break;
                int taken = (int) Math.min(remaining, r.count());
                out.put(r.slot(), taken);
                remaining -= taken;
            }
        }
        return out;
    }

    private static boolean hintsMatch(List<Row> current, List<craft.BankAllocation> hints) {
        if (hints == null || hints.size() != current.size()) return false;
        List<craft.BankAllocation> sorted = new ArrayList<>(hints);
        sorted.sort(java.util.Comparator.comparingInt(craft.BankAllocation::slot));
        for (int i = 0; i < current.size(); i++) {
            Row r = current.get(i);
            craft.BankAllocation h = sorted.get(i);
            if (h.slot() != r.slot() || h.itemId() != r.itemId() || h.observedCount() != r.count()
                    || !java.util.Objects.equals(h.binding(), r.binding())
                    || !java.util.Objects.equals(h.boundTo(), r.boundTo())) return false;
        }
        return true;
    }

    /** Per-item reserve for one store; fails when a partial target spans differently bound stacks. */
    private static Map<Integer, Long> reservationsToApply(List<Row> rows, Map<Integer, Integer> target) {
        Map<Integer, Long> out = new HashMap<>();
        if (target == null || target.isEmpty()) return out;
        Map<Integer, Long> totals = new HashMap<>();
        Map<Integer, Set<String>> kinds = new HashMap<>();
        for (Row r : rows) {
            if (!target.containsKey(r.itemId())) continue;
            totals.merge(r.itemId(), (long) r.count(), Long::sum);
            kinds.computeIfAbsent(r.itemId(), k -> new HashSet<>()).add(r.binding() + "\u0000" + r.boundTo());
        }
        for (Map.Entry<Integer, Long> t : totals.entrySet()) {
            long reserve = target.get(t.getKey());
            if (reserve <= 0 || t.getValue() <= 0) continue;
            if (reserve < t.getValue() && kinds.get(t.getKey()).size() > 1) {
                throw new UnresolvedReservationAllocationException(t.getKey());
            }
            out.put(t.getKey(), reserve);
        }
        return out;
    }

    /**
     * Thrown when a finite reservation would cover only part of one item's stacks in a store whose
     * stacks differ in binding or character owner. No allocation rule is approved (draft; Product
     * Owner decision pending), so no guess is made.
     */
    public static final class UnresolvedReservationAllocationException extends RuntimeException {
        public final int itemId;
        public UnresolvedReservationAllocationException(int itemId) {
            super("Partial material reservation across differently bound copies is not decided yet");
            this.itemId = itemId;
        }
    }

    private static Map<Integer, OwnedQuantity> combine(Map<Integer, Integer> sellable,
                                                       Map<Integer, Integer> bound) {
        Set<Integer> itemIds = new HashSet<>();
        itemIds.addAll(sellable.keySet());
        itemIds.addAll(bound.keySet());

        Map<Integer, OwnedQuantity> result = new HashMap<>();
        for (int itemId : itemIds) {
            result.put(itemId, new OwnedQuantity(sellable.getOrDefault(itemId, 0), bound.getOrDefault(itemId, 0)));
        }
        return result;
    }

    /**
     * "use mats from Bank" checked:
     * - include account_bank items + account_materials
     * unchecked:
     * - only account_materials
     *
     * Also includes item quantities held in character_items (all characters, all bag/equipment
     * locations) per DOMAIN_SPEC.md DQ-006 ("owned pool = material storage + bank + all character
     * inventories"). This legacy unfiltered aggregate ignores binding (binding/bound_to): every owned
     * character_items row counts toward the pool the same as bank/materials rows. Binding-aware
     * usable quantity (DOMAIN_SPEC.md DQ-007) is implemented separately by the selected-character
     * methods such as {@link #loadOwnedInventoryForCharacter(String)} and
     * {@link #loadOwnedInventoryForCharacters(Set)}, which split the pools by binding.
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
        // Aggregates owned quantity across account_materials, account_bank and character_items, all
        // of which carry a verified account GUID on a managed database: summing
        // them without a scope would mix accounts, so the unscoped path is refused there.
        ManagedSchema.rejectUnscopedAccountAccess(con, "owned inventory without an account scope");
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
        ManagedSchema.rejectUnscopedAccountAccess(con, "owned inventory without an account scope");
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

        return combine(sellable, bound);
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
     * Owned inventory for coordinated multi-character planning (DOMAIN_SPEC.md section 2.2.1): the shared account-wide ordinary/tradable pool, the shared account-wide
     * account-bound pool (usable by any character), and soulbound owned quantity split by owning
     * character - usable only by that exact character.
     */
    public record CoordinatedInventory(Map<Integer, Integer> sellable,
                                        Map<Integer, Integer> accountBound,
                                        Map<String, Map<Integer, Integer>> characterBound) {
    }

    public CoordinatedInventory loadOwnedInventoryForCharacters(Set<String> characterNames,
                                                                Set<Integer> excludedBankIds,
                                                                Set<Integer> excludedMaterialIds) throws SQLException {
        try (Connection con = Db.open()) {
            return loadOwnedInventoryForCharacters(con, null, characterNames, excludedBankIds, excludedMaterialIds);
        }
    }

    public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, model.AccountScope scope,
                                                                Set<String> characterNames,
                                                                Set<Integer> excludedBankIds,
                                                                Set<Integer> excludedMaterialIds) throws SQLException {
        return loadOwnedInventoryForCharacters(con, scope, characterNames, excludedBankIds, excludedMaterialIds,
                Map.of(), Map.of());
    }

    public CoordinatedInventory loadOwnedInventoryForCharacters(Set<String> characterNames,
                                                                Set<Integer> excludedBankIds,
                                                                Set<Integer> excludedMaterialIds,
                                                                Map<Integer, Integer> bankQuantities,
                                                                Map<Integer, Integer> materialQuantities) throws SQLException {
        return loadOwnedInventoryForCharacters(characterNames, excludedBankIds, excludedMaterialIds,
                bankQuantities, materialQuantities, List.of());
    }

    public CoordinatedInventory loadOwnedInventoryForCharacters(Set<String> characterNames,
                                                                Set<Integer> excludedBankIds,
                                                                Set<Integer> excludedMaterialIds,
                                                                Map<Integer, Integer> bankQuantities,
                                                                Map<Integer, Integer> materialQuantities,
                                                                List<craft.BankAllocation> bankAllocations) throws SQLException {
        try (Connection con = Db.open()) {
            return loadOwnedInventoryForCharacters(con, null, characterNames, excludedBankIds, excludedMaterialIds,
                    bankQuantities, materialQuantities, bankAllocations);
        }
    }

    /**
     * Source exclusions and finite reservations apply before aggregation; cached quantities and
     * character stock are untouched. A finite target is subtracted once per item and store across
     * its stacks (at most the owned amount). This overload passes no Bank slot
     * allocations; the overload taking {@code bankAllocations} implements Bank slot allocation. Only the
     * mixed-binding Materials case is unresolved: a partial reservation across stacks with different
     * bindings/owners has no approved allocation rule and fails with
     * {@link UnresolvedReservationAllocationException}.
     */
    public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, model.AccountScope scope,
                                                                Set<String> characterNames,
                                                                Set<Integer> excludedBankIds,
                                                                Set<Integer> excludedMaterialIds,
                                                                Map<Integer, Integer> bankQuantities,
                                                                Map<Integer, Integer> materialQuantities) throws SQLException {
        return loadOwnedInventoryForCharacters(con, scope, characterNames, excludedBankIds, excludedMaterialIds,
                bankQuantities, materialQuantities, List.of());
    }

    public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, model.AccountScope scope,
                                                                Set<String> characterNames,
                                                                Set<Integer> excludedBankIds,
                                                                Set<Integer> excludedMaterialIds,
                                                                Map<Integer, Integer> bankQuantities,
                                                                Map<Integer, Integer> materialQuantities,
                                                                List<craft.BankAllocation> bankAllocations) throws SQLException {
        if (scope == null) ManagedSchema.rejectUnscopedAccountAccess(con, "owned inventory without an account scope");
        else ManagedSchema.requireManagedAccountSchema(con, "owned inventory");
        Map<Integer, Integer> sellable = new HashMap<>();
        Map<Integer, Integer> accountBound = new HashMap<>();
        Map<String, Map<Integer, Integer>> characterBound = new HashMap<>();
        forEachScopedRow(con, scope, excludedBankIds, excludedMaterialIds, bankQuantities, materialQuantities, bankAllocations,
                (itemId, count, binding, boundTo) ->
                classifyCoordinatedRow(itemId, count, binding, boundTo, characterNames,
                        sellable, accountBound, characterBound));
        return new CoordinatedInventory(sellable, accountBound, characterBound);
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
        ManagedSchema.rejectUnscopedAccountAccess(con, "owned inventory without an account scope");
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
