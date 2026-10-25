package web;

import application.CraftingDiscoveryService;
import craft.CraftingSettings;
import repo.DiscChoice;
import web.dto.CraftingDiscoveryRequest;
import web.dto.CraftingDiscoveryResponse;
import web.dto.CraftingRowDto;

import java.util.List;

/**
 * Translation between the Crafting Discovery transport DTOs and the existing application/domain
 * inputs and results (STORY-API-002, TARGET_ARCHITECTURE.md §9: "Business rules must not be
 * implemented in controllers").
 *
 * <p>This class only defaults, validates and copies. It performs no recipe-eligibility check, no
 * discovery filtering, no inventory or price lookup and no crafting calculation - all of that
 * stays inside {@link CraftingDiscoveryService} and {@code craft.*}. It is stateless, so it is safe
 * to share across concurrent requests.
 */
final class CraftingDiscoveryApiMapper {

    /**
     * Defaults for settings omitted by the browser. Discovery has no cumulative purchase budget;
     * the internal planner setting is zero, which means unlimited for this single attempt.
     */
    static final boolean DEFAULT_USE_OWN_MATS = true;          // "use mats from Bank" checked
    static final boolean DEFAULT_ALLOW_BUYING = true;          // "buy missing mats" checked
    static final boolean DEFAULT_LISTING_SELL = false;         // instant sell
    static final boolean DEFAULT_LISTING_BUY = false;          // instant buy

    /** Not a request field: Discovery preserves its existing behavior of allowing daily crafts. */
    static final boolean FIXED_ALLOW_DAILY_CRAFTS = true;

    private CraftingDiscoveryApiMapper() {}

    /**
     * Applies the documented defaults to {@code request} (which may itself be null, i.e. an absent
     * body) and validates the outcome.
     *
     * @throws ApiValidationException if the completed request cannot describe a Discovery
     *                                calculation
     */
    static Effective toEffective(CraftingDiscoveryRequest request) {
        if (request == null || request.scope() == null) {
            throw new ApiValidationException(
                    "scope is required: Discovery lists the recipes missing for one discipline+character combination");
        }

        return new Effective(
                toEffectiveScope(request.scope()),
                toEffectiveSettings(request.settings()));
    }

    private static CraftingDiscoveryResponse.EffectiveScopeDto toEffectiveScope(
            CraftingDiscoveryRequest.ScopeDto scope) {

        String discipline = requirePresent(trimToNull(scope.discipline()), "scope.discipline");
        String characterName = requirePresent(trimToNull(scope.characterName()), "scope.characterName");

        if (scope.rating() == null) {
            throw new ApiValidationException(
                    "scope.rating is required: it filters out recipes above the character's crafting rating");
        }
        if (scope.rating() < 0) {
            throw new ApiValidationException("scope.rating must not be negative");
        }

        return new CraftingDiscoveryResponse.EffectiveScopeDto(discipline, characterName, scope.rating());
    }

    private static CraftingDiscoveryResponse.EffectiveSettingsDto toEffectiveSettings(
            CraftingDiscoveryRequest.SettingsDto settings) {

        return new CraftingDiscoveryResponse.EffectiveSettingsDto(
                orDefault(settings == null ? null : settings.useOwnMats(), DEFAULT_USE_OWN_MATS),
                orDefault(settings == null ? null : settings.allowBuying(), DEFAULT_ALLOW_BUYING),
                orDefault(settings == null ? null : settings.listingSell(), DEFAULT_LISTING_SELL),
                orDefault(settings == null ? null : settings.listingBuy(), DEFAULT_LISTING_BUY),
                FIXED_ALLOW_DAILY_CRAFTS);
    }

    /** A validated request, in both its transport form (echoed back) and its application form. */
    record Effective(CraftingDiscoveryResponse.EffectiveScopeDto scope,
                     CraftingDiscoveryResponse.EffectiveSettingsDto settings) {

        /**
         * Always a {@code CHAR_DISCIPLINE} choice: Discovery is individual-character only, the same
         * shape the JavaFX selector produces (CURRENT_ARCHITECTURE.md §5.2).
         */
        DiscChoice toDiscChoice() {
            return DiscChoice.charDiscipline(scope.discipline(), scope.rating(), scope.characterName());
        }

        CraftingSettings toCraftingSettings() {
            return new CraftingSettings(
                    settings.useOwnMats(),
                    settings.allowBuying(),
                    0,
                    settings.listingSell(),
                    settings.listingBuy(),
                    settings.allowDailyCrafts());
        }
    }

    /**
     * Maps one {@link CraftingDiscoveryService.DiscoveryData} into the transport response, in the
     * service's visible-recipe order. Row copying itself lives in {@link CraftingRowMapper}, shared
     * with Crafting Profit; an empty {@code DiscoveryData} - what the service returns when nothing
     * is left to discover - therefore maps to an empty row list rather than an error.
     */
    static CraftingDiscoveryResponse toResponse(Effective effective,
                                                CraftingDiscoveryService.DiscoveryData data) {
        List<CraftingRowDto> rows = CraftingRowMapper.toRows(
                data.visibleRecipes(), data.resultsByRecipeId(), data.items(), data.tp());

        return new CraftingDiscoveryResponse(effective.scope(), effective.settings(), rows.size(), rows);
    }

    private static String requirePresent(String value, String field) {
        if (value == null) {
            throw new ApiValidationException(field + " is required");
        }
        return value;
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
