package web;

import application.CraftingProfitService;
import craft.CraftingSettings;
import repo.DiscChoice;
import web.dto.CraftingProfitRequest;
import web.dto.CraftingProfitResponse;
import web.dto.CraftingRowDto;

import java.util.List;

/**
 * Translation between the HTTP transport DTOs and the existing application/domain inputs and
 * results (STORY-API-001, TARGET_ARCHITECTURE.md §9: "Business rules must not be implemented in
 * controllers").
 *
 * <p>This class only defaults, validates and copies. It performs no crafting calculation, derives
 * no total, and applies no display rule - every number it emits is the one the domain produced.
 * It is stateless, so it is safe to share across concurrent requests.
 */
final class CraftingProfitApiMapper {

    /**
     * Defaults for omitted request fields: the values the JavaFX Crafting Profit view opens with
     * (its initial control state), so an empty request body reproduces the default page load and
     * the two entry points stay comparable.
     */
    static final String DEFAULT_SCOPE_KIND = "ALL";
    static final boolean DEFAULT_USE_OWN_MATS = true;
    static final boolean DEFAULT_ALLOW_BUYING = false;
    static final int DEFAULT_MAX_BUY_COPPER = 10_000; // the view's "1g" budget field default
    static final boolean DEFAULT_LISTING_SELL = false; // instant sell
    static final boolean DEFAULT_LISTING_BUY = false;  // instant buy
    static final boolean DEFAULT_DAILY_BUY_INSTEAD_OF_CRAFT = true;

    private static final String SCOPE_ALL = "ALL";
    private static final String SCOPE_DISCIPLINE = "DISCIPLINE";
    private static final String SCOPE_CHARACTER_DISCIPLINE = "CHARACTER_DISCIPLINE";

    private CraftingProfitApiMapper() {}

    /**
     * Applies the documented defaults to {@code request} (which may itself be null) and validates
     * the outcome.
     *
     * @throws ApiValidationException if the completed request cannot describe a calculation
     */
    static Effective toEffective(CraftingProfitRequest request) {
        CraftingProfitRequest.ScopeDto scope = request == null ? null : request.scope();
        CraftingProfitRequest.SettingsDto settings = request == null ? null : request.settings();

        return new Effective(toEffectiveScope(scope), toEffectiveSettings(settings));
    }

    private static CraftingProfitResponse.EffectiveScopeDto toEffectiveScope(
            CraftingProfitRequest.ScopeDto scope) {

        String kind = scope == null || scope.kind() == null ? DEFAULT_SCOPE_KIND : scope.kind();
        String discipline = scope == null ? null : trimToNull(scope.discipline());
        String characterName = scope == null ? null : trimToNull(scope.characterName());
        int rating = scope == null || scope.rating() == null ? 0 : scope.rating();

        switch (kind) {
            case SCOPE_ALL -> {
                return new CraftingProfitResponse.EffectiveScopeDto(SCOPE_ALL, null, null, 0);
            }
            case SCOPE_DISCIPLINE -> {
                requirePresent(discipline, "scope.discipline", SCOPE_DISCIPLINE);
                return new CraftingProfitResponse.EffectiveScopeDto(SCOPE_DISCIPLINE, discipline, null, 0);
            }
            case SCOPE_CHARACTER_DISCIPLINE -> {
                requirePresent(discipline, "scope.discipline", SCOPE_CHARACTER_DISCIPLINE);
                requirePresent(characterName, "scope.characterName", SCOPE_CHARACTER_DISCIPLINE);
                if (rating < 0) {
                    throw new ApiValidationException("scope.rating must not be negative");
                }
                return new CraftingProfitResponse.EffectiveScopeDto(
                        SCOPE_CHARACTER_DISCIPLINE, discipline, characterName, rating);
            }
            default -> throw new ApiValidationException(
                    "scope.kind must be one of " + SCOPE_ALL + ", " + SCOPE_DISCIPLINE + ", "
                            + SCOPE_CHARACTER_DISCIPLINE);
        }
    }

    private static CraftingProfitResponse.EffectiveSettingsDto toEffectiveSettings(
            CraftingProfitRequest.SettingsDto settings) {

        int maxBuyCopper = settings == null || settings.maxBuyCopper() == null
                ? DEFAULT_MAX_BUY_COPPER
                : settings.maxBuyCopper();
        if (maxBuyCopper < 0) {
            throw new ApiValidationException("settings.maxBuyCopper must not be negative");
        }

        return new CraftingProfitResponse.EffectiveSettingsDto(
                orDefault(settings == null ? null : settings.useOwnMats(), DEFAULT_USE_OWN_MATS),
                orDefault(settings == null ? null : settings.allowBuying(), DEFAULT_ALLOW_BUYING),
                maxBuyCopper,
                orDefault(settings == null ? null : settings.listingSell(), DEFAULT_LISTING_SELL),
                orDefault(settings == null ? null : settings.listingBuy(), DEFAULT_LISTING_BUY),
                orDefault(settings == null ? null : settings.dailyBuyInsteadOfCraft(),
                        DEFAULT_DAILY_BUY_INSTEAD_OF_CRAFT));
    }

    /** A validated request, in both its transport form (echoed back) and its application form. */
    record Effective(CraftingProfitResponse.EffectiveScopeDto scope,
                     CraftingProfitResponse.EffectiveSettingsDto settings) {

        DiscChoice toDiscChoice() {
            return switch (scope.kind()) {
                case SCOPE_DISCIPLINE -> DiscChoice.disciplineOnly(scope.discipline());
                case SCOPE_CHARACTER_DISCIPLINE ->
                        DiscChoice.charDiscipline(scope.discipline(), scope.rating(), scope.characterName());
                default -> DiscChoice.all();
            };
        }

        CraftingSettings toCraftingSettings() {
            return new CraftingSettings(
                    settings.useOwnMats(),
                    settings.allowBuying(),
                    settings.maxBuyCopper(),
                    settings.listingSell(),
                    settings.listingBuy(),
                    settings.dailyBuyInsteadOfCraft());
        }
    }

    /**
     * Maps one {@link CraftingProfitService.ProfitData} into the transport response, in the
     * repository's visible-recipe order. Row copying itself lives in {@link CraftingRowMapper},
     * shared with Crafting Discovery.
     */
    static CraftingProfitResponse toResponse(Effective effective, CraftingProfitService.ProfitData data) {
        List<CraftingRowDto> rows = CraftingRowMapper.toRows(
                data.visibleRecipes(), data.resultsByRecipeId(), data.items(), data.tp());

        return new CraftingProfitResponse(effective.scope(), effective.settings(), rows.size(), rows);
    }

    private static void requirePresent(String value, String field, String kind) {
        if (value == null) {
            throw new ApiValidationException(field + " is required for scope.kind " + kind);
        }
    }

    private static boolean orDefault(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
