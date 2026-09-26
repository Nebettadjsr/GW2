package web.dto;

import java.util.List;

/**
 * Transport response body for {@code GET /api/crafting/selector-options} (STORY-API-006,
 * TARGET_ARCHITECTURE.md §9).
 *
 * <p>Carries everything a browser needs to build Crafting Profit's single scope selector
 * (DOMAIN_SPEC.md §2.2.1) and nothing else: which entry is preselected, the generic discipline
 * entries, and one entry per synced character discipline. Each field maps directly onto a
 * {@link CraftingProfitRequest.ScopeDto} the caller can post back, so the browser never has to
 * reproduce an eligibility rule or reach the database itself.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 * In particular {@code characterOptions} is a copy of the application service's rows, not a
 * serialized {@code repo.CharacterRepository.DiscRow}.
 *
 * @param defaultScopeKind    the scope kind a caller should preselect ({@code ALL}), the same
 *                            default {@code POST /api/crafting/profit} applies to an empty body
 * @param disciplines         the generic discipline entries, in their established display order;
 *                            each names a {@code DISCIPLINE} scope and keeps that scope's meaning
 *                            (all synced characters having that discipline)
 * @param characterOptionCount size of {@code characterOptions}, so a caller can tell an empty
 *                            result from a truncated one without counting
 * @param characterOptions    one entry per synced character discipline, in the application
 *                            service's order; empty when nothing is synced
 */
public record CraftingSelectorOptionsResponse(String defaultScopeKind,
                                              List<String> disciplines,
                                              int characterOptionCount,
                                              List<CharacterOptionDto> characterOptions) {

    /**
     * One synced character discipline, as reported by
     * {@code application.CharacterSelectionService.getCraftingCharacterOptions()}.
     *
     * <p>{@code characterName}, {@code discipline} and {@code rating} are exactly the three fields a
     * {@code CHARACTER_DISCIPLINE} scope needs. {@code active} is the service-provided fact that the
     * character currently has this discipline active; it is reported rather than dropped because the
     * caller cannot re-derive it, and no rule is applied to it here.
     */
    public record CharacterOptionDto(String characterName,
                                     String discipline,
                                     int rating,
                                     boolean active) {
    }
}
