package craft;

/**
 * Recipe-known policy (DOMAIN_SPEC.md sections 6, 34, 35; DQ-010). Normal Guild Wars 2 recipe
 * unlocks are account-wide: once any character unlocks a recipe, every character on the account is
 * considered to know it - recipe *ownership* is a single account-wide fact, the same for every
 * caller and every character. This class expresses that "is this recipe known" decision as a pure
 * function of unlock facts supplied by persistence (TARGET_ARCHITECTURE.md section 10's mapping
 * boundary) - it has no JDBC/SQL/repository/transport dependency (TARGET_ARCHITECTURE.md section 7).
 *
 * Ownership is independent of whether the selected character is currently eligible to craft or
 * discover the recipe (crafting discipline, minimum rating, discoverability flags); callers apply
 * those checks separately from this policy (STORY-DOM-019).
 */
public final class RecipeKnowledgePolicy {

    private RecipeKnowledgePolicy() {}

    /**
     * True if the recipe is known account-wide: unlocked via the account's own unlock record, or
     * via any character's unlock record (DOMAIN_SPEC.md section 34 - "unlocked anywhere on the
     * account, regardless of which character originally discovered or learned it"). Used for every
     * knowledge decision - including character-scoped loading - since recipe ownership itself does
     * not vary by which character is selected (DQ-010).
     */
    public static boolean isKnownAccountWide(boolean unlockedForAccount, boolean unlockedByAnyCharacter) {
        return unlockedForAccount || unlockedByAnyCharacter;
    }
}
