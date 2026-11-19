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
     * One account's total owned quantity per item, binding deliberately not filtered.
     *
     * <p>The pool is material storage plus bank plus every character's bags and equipment
     * (DOMAIN_SPEC.md DQ-006), with the owning account on each of the three reads. Summing them
     * without that scope would mix two accounts' quantities into one number.
     */
    public Map<Integer, Integer> loadOwnedInventory(Connection con, model.AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "owned inventory");
        Map<Integer, Integer> inv = new HashMap<>();
        forEachScopedRow(con, scope, (itemId, count, binding, boundTo) -> inv.merge(itemId, count, Integer::sum));
        return inv;
    }

    /**
     * One account's owned quantity split by binding usability for {@code selectedCharacterName} (DOMAIN_SPEC.md section 11.1 / DQ-007).
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
     * One account's three-way coordinated inventory split.
     */
    public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con,
                                                                model.AccountScope scope,
                                                                Set<String> characterNames)
            throws SQLException {
        return loadOwnedInventoryForCharacters(con, scope, characterNames, false);
    }

    /**
     * @param keepAllCarriedOwners keep carried soulbound stock of EVERY character of this account (not only
     *        {@code characterNames}) under its actual owner, for Mystic Forge owner steps; ordinary steps still
     *        only ever use the assigned roster character's entry.
     */
    public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con,
                                                                model.AccountScope scope,
                                                                Set<String> characterNames,
                                                                boolean keepAllCarriedOwners)
            throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "owned inventory");
        Map<Integer, Integer> sellable = new HashMap<>();
        Map<Integer, Integer> accountBound = new HashMap<>();
        Map<String, Map<Integer, Integer>> characterBound = new HashMap<>();
        forEachScopedRow(con, scope, Set.of(), Set.of(), Map.of(), Map.of(), List.of(),
                coordinatedHandler(characterNames, keepAllCarriedOwners, sellable, accountBound, characterBound));
        return new CoordinatedInventory(sellable, accountBound, characterBound);
    }

    /** One owned row as every pool reports it. */
    private interface OwnedRowHandler {
        void accept(int itemId, int count, String binding, String boundTo);

        /** Same row plus the {@link #SCOPED_POOL_QUERIES} index it came from (1 = Bank). */
        default void acceptPooled(int pool, int itemId, int count, String binding, String boundTo) {
            accept(itemId, count, binding, boundTo);
        }
    }

    private static final int BANK_POOL = 1;

    /**
     * Coordinated classification that keeps Bank provenance: character-bound Bank stock is NOT
     * folded into the carried per-character map. It is stored under {@link craft.PlanState#bankOwnerKey}
     * (whatever the owner, roster member or not), so only a Forge step (any owner) or the owner's own ordinary
     * step can spend it, and it is debited once from that single entry.
     */
    private static OwnedRowHandler coordinatedHandler(Set<String> characterNames, boolean keepAllCarriedOwners,
                                                      Map<Integer, Integer> sellable,
                                                      Map<Integer, Integer> accountBound,
                                                      Map<String, Map<Integer, Integer>> characterBound) {
        return new OwnedRowHandler() {
            @Override public void accept(int itemId, int count, String binding, String boundTo) {
                classifyCoordinatedRow(itemId, count, binding, boundTo, characterNames, keepAllCarriedOwners,
                        sellable, accountBound, characterBound);
            }

            @Override public void acceptPooled(int pool, int itemId, int count, String binding, String boundTo) {
                if (pool == BANK_POOL && "Character".equals(binding)) {
                    characterBound.computeIfAbsent(craft.PlanState.bankOwnerKey(boundTo), k -> new HashMap<>())
                            .merge(itemId, count, Integer::sum);
                    return;
                }
                accept(itemId, count, binding, boundTo);
            }
        };
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
            Set<Integer> excluded = pool == 0 ? excludedMaterialIds : pool == 1 ? excludedBankIds : Set.of();
            Map<Integer, Integer> target = pool == 0 ? materialQuantities : pool == 1 ? bankQuantities : Map.of();
            if (target == null || target.isEmpty()) {
                // No finite target for this pool: keep the original streaming path, no buffering.
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setObject(1, scope.accountId());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int itemId = rs.getInt("item_id");
                            if (excluded.contains(itemId)) continue;
                            int count = rs.getInt("count");
                            if (count > 0) handler.acceptPooled(pool, itemId, count, rs.getString("binding"), rs.getString("bound_to"));
                        }
                    }
                }
                continue;
            }
            // Homogeneous finite allocation needs every stack of a targeted item, so only this pool is buffered.
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setObject(1, scope.accountId());
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
                if (count > 0) handler.acceptPooled(pool, r.itemId(), count, r.binding(), r.boundTo());
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
     * character - usable only by that exact character. Bank-resident soulbound stock is kept apart under
     * {@link craft.PlanState#bankOwnerKey} keys (Forge-usable by any owner, see DOMAIN_SPEC).
     */
    public record CoordinatedInventory(Map<Integer, Integer> sellable,
                                        Map<Integer, Integer> accountBound,
                                        Map<String, Map<Integer, Integer>> characterBound) {
    }

    public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, model.AccountScope scope,
                                                                Set<String> characterNames,
                                                                Set<Integer> excludedBankIds,
                                                                Set<Integer> excludedMaterialIds) throws SQLException {
        return loadOwnedInventoryForCharacters(con, scope, characterNames, excludedBankIds, excludedMaterialIds,
                Map.of(), Map.of());
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
        return loadOwnedInventoryForCharacters(con, scope, characterNames, excludedBankIds, excludedMaterialIds,
                bankQuantities, materialQuantities, bankAllocations, false);
    }

    public CoordinatedInventory loadOwnedInventoryForCharacters(Connection con, model.AccountScope scope,
                                                                Set<String> characterNames,
                                                                Set<Integer> excludedBankIds,
                                                                Set<Integer> excludedMaterialIds,
                                                                Map<Integer, Integer> bankQuantities,
                                                                Map<Integer, Integer> materialQuantities,
                                                                List<craft.BankAllocation> bankAllocations,
                                                                boolean keepAllCarriedOwners) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "owned inventory");
        Map<Integer, Integer> sellable = new HashMap<>();
        Map<Integer, Integer> accountBound = new HashMap<>();
        Map<String, Map<Integer, Integer>> characterBound = new HashMap<>();
        forEachScopedRow(con, scope, excludedBankIds, excludedMaterialIds, bankQuantities, materialQuantities, bankAllocations,
                coordinatedHandler(characterNames, keepAllCarriedOwners, sellable, accountBound, characterBound));
        return new CoordinatedInventory(sellable, accountBound, characterBound);
    }

    private static void classifyCoordinatedRow(int itemId, int count, String binding, String boundTo,
                                                 Set<String> characterNames, boolean keepAllCarriedOwners,
                                                 Map<Integer, Integer> sellable,
                                                 Map<Integer, Integer> accountBound,
                                                 Map<String, Map<Integer, Integer>> characterBound) {
        if ("Character".equals(binding)) {
            if (boundTo != null && (keepAllCarriedOwners || characterNames.contains(boundTo))) {
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
