package craft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain unit test (docs/TEST_STRATEGY.md domain layer) for {@link RecipeKnowledgePolicy}:
 * exercises the policy on plain boolean unlock facts, with no database, repository, or
 * persistence involvement (STORY-DOM-018). {@code isKnownAccountWide} is the single ownership
 * policy for every repository entry point, including character-scoped loading (STORY-DOM-019).
 */
class RecipeKnowledgePolicyTest {

    @Test
    void isKnownAccountWide_trueWhenUnlockedForAccountOnly() {
        assertTrue(RecipeKnowledgePolicy.isKnownAccountWide(true, false));
    }

    @Test
    void isKnownAccountWide_trueWhenUnlockedByAnyCharacterOnly() {
        assertTrue(RecipeKnowledgePolicy.isKnownAccountWide(false, true));
    }

    @Test
    void isKnownAccountWide_trueWhenUnlockedByBoth() {
        assertTrue(RecipeKnowledgePolicy.isKnownAccountWide(true, true));
    }

    @Test
    void isKnownAccountWide_falseWhenUnlockedByNeither() {
        assertFalse(RecipeKnowledgePolicy.isKnownAccountWide(false, false));
    }
}
