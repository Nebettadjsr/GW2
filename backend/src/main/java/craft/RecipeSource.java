package craft;

/**
 * Origin of a recipe. Mystic Forge recipes come from the bundled, validated GW2 Wiki catalog, are
 * identified by a negative recipe id (the negated wiki forge id) so they can never collide with an
 * ArenaNet recipe id, an account unlock or an ordinary selected-detail id, and need no crafting
 * discipline, rating or unlock.
 */
public enum RecipeSource {
    ORDINARY,
    MYSTIC_FORGE,
    /** Verified fixed-price vendor purchase of a Forge input; synthetic, never a root, unlock or Discovery candidate. */
    VENDOR
}
