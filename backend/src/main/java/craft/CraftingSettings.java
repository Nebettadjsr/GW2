package craft;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class CraftingSettings {
    public final boolean useOwnMats;
    public final boolean allowBuying;
    public final int maxBuyCopper;
    public final boolean listingSell;   // false=instant sell, true=listing sell
    public final boolean listingBuy;
    public final boolean allowDailyCrafts;
    /** Owner opt-in (default off): Mystic Forge recipes, roots and nested routes, join the calculation. */
    public final boolean allowMysticForge;

    /** Material Protection (DOMAIN_SPEC.md): item IDs whose pre-existing owned copies are unavailable. */
    public final boolean materialProtectionEnabled;
    /** Immutable request-local membership set; empty unless protection is enabled with a selection. */
    public final Set<Integer> protectedItemIds;
    public final Set<Integer> protectedBankItemIds;
    public final Set<Integer> protectedMaterialItemIds;
    /** Finite per-source reservation targets (item id -> units to keep); immutable, positive values only. */
    public final Map<Integer, Integer> protectedBankQuantities;
    public final Map<Integer, Integer> protectedMaterialQuantities;
    /** Canonical manual Bank row intent (ascending slot); a guard over the fixed totals, never authority. */
    public final List<BankAllocation> protectedBankAllocations;

    public CraftingSettings(boolean useOwnMats,
                            boolean allowBuy,
                            int maxBuyCopper,
                            boolean listingSell,
                            boolean listingBuy,
                            boolean allowDailyCrafts) {
        this(useOwnMats, allowBuy, maxBuyCopper, listingSell, listingBuy, allowDailyCrafts,
                false, Set.of());
    }

    public CraftingSettings(boolean useOwnMats,
                            boolean allowBuy,
                            int maxBuyCopper,
                            boolean listingSell,
                            boolean listingBuy,
                            boolean allowDailyCrafts,
                            boolean materialProtectionEnabled,
                            Collection<Integer> protectedItemIds) {
        this(useOwnMats, allowBuy, maxBuyCopper, listingSell, listingBuy, allowDailyCrafts,
                materialProtectionEnabled, protectedItemIds, Set.of(), Set.of());
    }

    public CraftingSettings(boolean useOwnMats, boolean allowBuy, int maxBuyCopper,
                            boolean listingSell, boolean listingBuy, boolean allowDailyCrafts,
                            boolean materialProtectionEnabled, Collection<Integer> protectedItemIds,
                            Collection<Integer> protectedBankItemIds, Collection<Integer> protectedMaterialItemIds) {
        this(useOwnMats, allowBuy, maxBuyCopper, listingSell, listingBuy, allowDailyCrafts,
                materialProtectionEnabled, protectedItemIds, protectedBankItemIds, protectedMaterialItemIds,
                Map.of(), Map.of());
    }

    public CraftingSettings(boolean useOwnMats, boolean allowBuy, int maxBuyCopper,
                            boolean listingSell, boolean listingBuy, boolean allowDailyCrafts,
                            boolean materialProtectionEnabled, Collection<Integer> protectedItemIds,
                            Collection<Integer> protectedBankItemIds, Collection<Integer> protectedMaterialItemIds,
                            Map<Integer, Integer> protectedBankQuantities,
                            Map<Integer, Integer> protectedMaterialQuantities) {
        this(useOwnMats, allowBuy, maxBuyCopper, listingSell, listingBuy, allowDailyCrafts,
                materialProtectionEnabled, protectedItemIds, protectedBankItemIds, protectedMaterialItemIds,
                protectedBankQuantities, protectedMaterialQuantities, List.of());
    }

    public CraftingSettings(boolean useOwnMats, boolean allowBuy, int maxBuyCopper,
                            boolean listingSell, boolean listingBuy, boolean allowDailyCrafts,
                            boolean materialProtectionEnabled, Collection<Integer> protectedItemIds,
                            Collection<Integer> protectedBankItemIds, Collection<Integer> protectedMaterialItemIds,
                            Map<Integer, Integer> protectedBankQuantities,
                            Map<Integer, Integer> protectedMaterialQuantities,
                            List<BankAllocation> protectedBankAllocations) {
        this(useOwnMats, allowBuy, maxBuyCopper, listingSell, listingBuy, allowDailyCrafts,
                materialProtectionEnabled, protectedItemIds, protectedBankItemIds, protectedMaterialItemIds,
                protectedBankQuantities, protectedMaterialQuantities, protectedBankAllocations, false);
    }

    public CraftingSettings(boolean useOwnMats, boolean allowBuy, int maxBuyCopper,
                            boolean listingSell, boolean listingBuy, boolean allowDailyCrafts,
                            boolean materialProtectionEnabled, Collection<Integer> protectedItemIds,
                            Collection<Integer> protectedBankItemIds, Collection<Integer> protectedMaterialItemIds,
                            Map<Integer, Integer> protectedBankQuantities,
                            Map<Integer, Integer> protectedMaterialQuantities,
                            List<BankAllocation> protectedBankAllocations,
                            boolean allowMysticForge) {
        this.allowMysticForge = allowMysticForge;
        this.protectedBankAllocations = protectedBankAllocations == null ? List.of()
                : List.copyOf(protectedBankAllocations);
        this.protectedBankQuantities = canonicalQuantities(protectedBankQuantities, protectedBankItemIds, protectedItemIds);
        this.protectedMaterialQuantities = canonicalQuantities(protectedMaterialQuantities, protectedMaterialItemIds, protectedItemIds);
        this.materialProtectionEnabled = materialProtectionEnabled;
        this.protectedItemIds = protectedItemIds == null ? Set.of() : Set.copyOf(protectedItemIds);
        this.protectedBankItemIds = protectedBankItemIds == null ? Set.of() : Set.copyOf(protectedBankItemIds);
        this.protectedMaterialItemIds = protectedMaterialItemIds == null ? Set.of() : Set.copyOf(protectedMaterialItemIds);
        this.useOwnMats = useOwnMats;
        this.allowBuying = allowBuy;
        this.maxBuyCopper = maxBuyCopper;
        this.listingSell = listingSell;
        this.listingBuy = listingBuy;
        this.allowDailyCrafts = allowDailyCrafts;
    }

    /** Immutable copy keeping positive targets only; reserve-ALL for the same item takes precedence. */
    private static Map<Integer, Integer> canonicalQuantities(Map<Integer, Integer> quantities,
                                                              Collection<Integer> sourceAll,
                                                              Collection<Integer> globalAll) {
        if (quantities == null || quantities.isEmpty()) return Map.of();
        Map<Integer, Integer> out = new TreeMap<>();
        quantities.forEach((id, qty) -> {
            if (id == null || qty == null || qty <= 0) return;
            if (sourceAll != null && sourceAll.contains(id)) return;
            if (globalAll != null && globalAll.contains(id)) return;
            out.put(id, qty);
        });
        return Map.copyOf(out);
    }

    /** True when any source-specific finite reservation is present. */
    public boolean hasFiniteReservations() {
        return materialProtectionEnabled
                && (!protectedBankQuantities.isEmpty() || !protectedMaterialQuantities.isEmpty());
    }

    /** True when pre-existing owned copies of {@code itemId} must not be consumed. */
    public boolean isProtected(int itemId) {
        return materialProtectionEnabled && !protectedItemIds.isEmpty()
                && protectedItemIds.contains(itemId);
    }

    /** Same settings with buying disabled and no budget; protection is preserved. */
    public CraftingSettings withBuyingDisabled() {
        return new CraftingSettings(useOwnMats, false, 0, listingSell, listingBuy, allowDailyCrafts,
                materialProtectionEnabled, protectedItemIds, protectedBankItemIds, protectedMaterialItemIds,
                protectedBankQuantities, protectedMaterialQuantities, protectedBankAllocations, allowMysticForge);
    }

    /** Crafting Profit is ordinary crafting only: a stale Forge opt-in never survives this boundary. */
    public static CraftingSettings ordinaryOnly(CraftingSettings settings) {
        return settings == null || !settings.allowMysticForge ? settings : settings.withMysticForge(false);
    }

    /** Same settings with the Mystic Forge opt-in set; everything else is preserved. */
    public CraftingSettings withMysticForge(boolean allow) {
        return new CraftingSettings(useOwnMats, allowBuying, maxBuyCopper, listingSell, listingBuy, allowDailyCrafts,
                materialProtectionEnabled, protectedItemIds, protectedBankItemIds, protectedMaterialItemIds,
                protectedBankQuantities, protectedMaterialQuantities, protectedBankAllocations, allow);
    }

}
