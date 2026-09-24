import org.junit.jupiter.api.Test;
import repo.DiscChoice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-PERF-001: opening Crafting Profit used to run the whole pipeline twice - once from the
 * view's own initial reload, and once more when the background-loaded Discipline selector called
 * {@code selectFirst()} and moved its value from {@code null} to "All". The listener now reloads
 * only when the value change actually means a different scope.
 *
 * <p>That suppression is only safe while {@code sameScope} is exactly as strict as
 * {@code CraftingProfitService.reload}: these tests pin both halves - that null/All really are one
 * scope, and that no genuinely different selection is ever mistaken for the current one, which
 * would leave the user looking at results for the scope they just navigated away from.
 */
class CraftingProfitViewScopeChangeTest {

    @Test
    void nullAndAllAreTheSameScope() {
        // CraftingProfitService.reload() maps both to loadRecipes("All") with an unrestricted
        // roster, so a null -> All transition cannot change a single displayed value.
        assertTrue(CraftingProfitView.sameScope(null, DiscChoice.all()));
        assertTrue(CraftingProfitView.sameScope(DiscChoice.all(), null));
        assertTrue(CraftingProfitView.sameScope(DiscChoice.all(), DiscChoice.all()));
        assertTrue(CraftingProfitView.sameScope(null, null));
    }

    @Test
    void allDiffersFromEveryNarrowerScope() {
        assertFalse(CraftingProfitView.sameScope(DiscChoice.all(), DiscChoice.disciplineOnly("Chef")));
        assertFalse(CraftingProfitView.sameScope(null, DiscChoice.disciplineOnly("Chef")));
        assertFalse(CraftingProfitView.sameScope(DiscChoice.all(), DiscChoice.charDiscipline("Chef", 400, "Hero")));
        assertFalse(CraftingProfitView.sameScope(null, DiscChoice.charDiscipline("Chef", 400, "Hero")));
    }

    @Test
    void differentDisciplinesAreDifferentScopes() {
        assertFalse(CraftingProfitView.sameScope(
                DiscChoice.disciplineOnly("Chef"), DiscChoice.disciplineOnly("Tailor")));
        assertTrue(CraftingProfitView.sameScope(
                DiscChoice.disciplineOnly("Chef"), DiscChoice.disciplineOnly("Chef")));
    }

    @Test
    void aDisciplineAloneDiffersFromThatDisciplineOnACharacter() {
        assertFalse(CraftingProfitView.sameScope(
                DiscChoice.disciplineOnly("Chef"), DiscChoice.charDiscipline("Chef", 400, "Hero")));
    }

    @Test
    void differentCharactersOrDisciplinesAreDifferentScopes() {
        assertFalse(CraftingProfitView.sameScope(
                DiscChoice.charDiscipline("Chef", 400, "Hero"),
                DiscChoice.charDiscipline("Chef", 400, "Other")));
        assertFalse(CraftingProfitView.sameScope(
                DiscChoice.charDiscipline("Chef", 400, "Hero"),
                DiscChoice.charDiscipline("Tailor", 400, "Hero")));
    }

    @Test
    void ratingIsNotPartOfTheScope() {
        // reload() passes only charName and discipline to the repository; the rating shown in the
        // selector entry never reaches the query, so it cannot change the result.
        assertTrue(CraftingProfitView.sameScope(
                DiscChoice.charDiscipline("Chef", 400, "Hero"),
                DiscChoice.charDiscipline("Chef", 275, "Hero")));
    }
}
