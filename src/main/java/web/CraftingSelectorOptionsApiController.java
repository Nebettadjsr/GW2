package web;

import application.CharacterSelectionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repo.CharacterRepository;
import web.dto.CraftingSelectorOptionsResponse;

import java.sql.SQLException;
import java.util.List;

/**
 * HTTP boundary for the crafting scope-selector read (STORY-API-006, TARGET_ARCHITECTURE.md §9):
 * the options a browser needs to build Crafting Profit's single Discipline selector
 * (DOMAIN_SPEC.md §2.2.1) and post a scope back to {@code POST /api/crafting/profit}.
 *
 * <p>Thin by construction: it calls the existing {@link CharacterSelectionService} read the JavaFX
 * selectors already use (§5.1/§5.2 of CURRENT_ARCHITECTURE.md) and copies the rows into transport
 * records. It queries no repository, computes no eligibility and re-orders nothing - the character
 * options come back in exactly the order and with exactly the values the service reported.
 *
 * <p>Unlike the calculation routes this one holds a single shared service instance: the service
 * keeps no per-call state, so there is no reload result for concurrent requests to observe.
 */
@RestController
@RequestMapping("/api/crafting")
public class CraftingSelectorOptionsApiController {

    /**
     * The generic discipline entries, in the order the JavaFX Crafting Profit selector lists them
     * between {@code All} and the character-specific entries.
     *
     * <p>Mirrored here for the same reason {@link CraftingProfitApiMapper}'s defaults are: this is
     * the JavaFX view's established presentation list, not a fact any application service reports,
     * and the two entry points must offer the caller the same choices. Selecting one still means
     * what it has always meant - a {@code DISCIPLINE} scope covering all synced characters having
     * that discipline - and nothing here narrows that set.
     */
    static final List<String> GENERIC_DISCIPLINES = List.of(
            "Chef", "Huntsman", "Weaponsmith", "Armorsmith", "Artificer",
            "Tailor", "Leatherworker", "Jeweler", "Scribe");

    private final CharacterSelectionService characterSelectionService;

    public CraftingSelectorOptionsApiController(CharacterSelectionService characterSelectionService) {
        this.characterSelectionService = characterSelectionService;
    }

    /**
     * Returns the scope options for the crafting selectors.
     *
     * <p>Status contract: 200 with the options; 503 when the database is unavailable; 500 when the
     * read fails for any other reason. See {@link CraftingSelectorOptionsApiExceptionHandler}.
     * A database with nothing synced is a 200 with an empty {@code characterOptions} list - no
     * character is substituted, and the {@code All} and generic discipline entries are unaffected.
     */
    @GetMapping(path = "/selector-options", produces = MediaType.APPLICATION_JSON_VALUE)
    public CraftingSelectorOptionsResponse selectorOptions() throws SQLException {
        List<CraftingSelectorOptionsResponse.CharacterOptionDto> characterOptions =
                toCharacterOptions(characterSelectionService.getCraftingCharacterOptions());

        return new CraftingSelectorOptionsResponse(
                CraftingProfitApiMapper.DEFAULT_SCOPE_KIND,
                GENERIC_DISCIPLINES,
                characterOptions.size(),
                characterOptions);
    }

    /** Field-for-field copy, preserving the service's order; no filtering, sorting or defaulting. */
    private static List<CraftingSelectorOptionsResponse.CharacterOptionDto> toCharacterOptions(
            List<CharacterRepository.DiscRow> rows) {

        return rows.stream()
                .map(row -> new CraftingSelectorOptionsResponse.CharacterOptionDto(
                        row.charName, row.discipline, row.rating, row.active))
                .toList();
    }
}
