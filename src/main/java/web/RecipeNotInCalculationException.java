package web;

/**
 * A valid recipe ID that the detail operation's own fresh visible candidate set does not contain
 * (STORY-API-008, TARGET_ARCHITECTURE.md §13.4). {@link ApiExceptionHandler} maps it to HTTP 404
 * with the error code {@code RECIPE_NOT_IN_CALCULATION}.
 *
 * <p>It means "not part of this calculation" - including a recipe that is no longer discoverable in
 * this scope - and never asserts that the recipe is absent from the database. Its message carries
 * the caller's own recipe ID only, so it is safe to return.
 */
public class RecipeNotInCalculationException extends RuntimeException {

    public RecipeNotInCalculationException(int recipeId) {
        super("Recipe " + recipeId + " is not part of this calculation's visible recipes");
    }
}
