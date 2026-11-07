package craft;

import java.util.Collection;
import java.util.Set;

public class CraftingSettings {
    public final boolean useOwnMats;
    public final boolean allowBuying;
    public final int maxBuyCopper;
    public final boolean listingSell;   // false=instant sell, true=listing sell
    public final boolean listingBuy;
    public final boolean allowDailyCrafts;

    /** Material Protection (DOMAIN_SPEC.md): item IDs whose pre-existing owned copies are unavailable. */
    public final boolean materialProtectionEnabled;
    /** Immutable request-local membership set; empty unless protection is enabled with a selection. */
    public final Set<Integer> protectedItemIds;

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
        this.materialProtectionEnabled = materialProtectionEnabled;
        this.protectedItemIds = protectedItemIds == null ? Set.of() : Set.copyOf(protectedItemIds);
        this.useOwnMats = useOwnMats;
        this.allowBuying = allowBuy;
        this.maxBuyCopper = maxBuyCopper;
        this.listingSell = listingSell;
        this.listingBuy = listingBuy;
        this.allowDailyCrafts = allowDailyCrafts;
    }

    /** True when pre-existing owned copies of {@code itemId} must not be consumed. */
    public boolean isProtected(int itemId) {
        return materialProtectionEnabled && !protectedItemIds.isEmpty()
                && protectedItemIds.contains(itemId);
    }

    /** Same settings with buying disabled and no budget; protection is preserved. */
    public CraftingSettings withBuyingDisabled() {
        return new CraftingSettings(useOwnMats, false, 0, listingSell, listingBuy, allowDailyCrafts,
                materialProtectionEnabled, protectedItemIds);
    }

}
