package craft;

import java.util.List;

/**
 * Mystic Forge recipes whose outcome is random and unverified (Mystic Clover). They are browse/reference data only:
 * a separate type so {@link Recipe}'s fixed/{@link ExpectedYield} contract is never weakened, and they are never
 * registered as a producer of any item (a nested Clover demand stays an acquire-leaf). No output count, profit or
 * full-drop economics is claimed. Facts come from the already pinned raw capture (page revision 3201851).
 */
public final class ForgeReferenceRecipes {

    /** Synthetic ids share the Forge negative range but a distinct block so they never collide with catalog ids. */
    public static final int ID_BASE = 1_100_000_000;
    public static final String SOURCE_PAGE = "Mystic Clover";
    public static final long SOURCE_REVISION = 3201851L;

    public record Reference(int recipeId, int outputItemId, int possibleOutputs, List<Ingredient> ingredients,
                            String wikiUrl, long revision) {
        /** A planning-only target (never a producer): same inputs, one nominal output, MYSTIC_FORGE source. */
        public Recipe asPlanningTarget() {
            return new Recipe(recipeId, outputItemId, 1, 0, "", ingredients, List.of(), RecipeSource.MYSTIC_FORGE);
        }
    }

    private static final String URL = "https://wiki.guildwars2.com/wiki/Mystic_Clover";

    private static final List<Reference> ALL = List.of(
            new Reference(-(ID_BASE + 1), 19675, 1,
                    List.of(new Ingredient(19925, 1), new Ingredient(19976, 1), new Ingredient(19721, 1),
                            new Ingredient(20796, 6)), URL, SOURCE_REVISION),
            new Reference(-(ID_BASE + 2), 19675, 10,
                    List.of(new Ingredient(19925, 10), new Ingredient(19976, 10), new Ingredient(19721, 10),
                            new Ingredient(20799, 10)), URL, SOURCE_REVISION));

    private ForgeReferenceRecipes() { }

    public static List<Reference> all() { return ALL; }

    public static Reference byId(int recipeId) {
        return ALL.stream().filter(r -> r.recipeId() == recipeId).findFirst().orElse(null);
    }
}
