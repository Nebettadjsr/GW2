package web.dto;

/** Request for Discovery candidates. The required scope's character also owns the inventory context. */
public record CraftingDiscoveryRequest(ScopeDto scope, SettingsDto settings) {
    public record ScopeDto(String discipline, String characterName, Integer rating) { }

    /** Discovery evaluates one attempt and therefore has no cumulative max-buy budget. */
    public record SettingsDto(Boolean useOwnMats,
                              Boolean allowBuying,
                              Boolean listingSell,
                              Boolean listingBuy) { }
}
