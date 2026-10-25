package web.dto;

import java.util.List;

/**
 * Transport response body for {@code POST /api/crafting/profit} (STORY-API-001,
 * TARGET_ARCHITECTURE.md §9 and §9.1).
 *
 * <p>Carries the effective scope/settings actually used (so a caller that relied on defaults can
 * see what was applied) and one row per recipe visible in that scope. Row content, including what
 * is deliberately left out of it, is documented on {@link CraftingRowDto}, which this route shares
 * with Crafting Discovery (STORY-API-002).
 *
 * <p>One envelope serves both outcomes of §9.1's cached-result contract, so a client parses one
 * shape:
 *
 * <ul>
 *   <li>{@code status} {@code CALCULATED} (HTTP 200): {@code rowCount} and {@code rows} are
 *       present and are the ordinary authoritative result — including when
 *       {@code freshness.provisional} is true, which says the inputs had expired, not that the
 *       numbers are approximate.</li>
 *   <li>{@code status} {@code PENDING} (HTTP 202): no usable synchronized inputs existed, so
 *       {@code rowCount} and {@code rows} are absent rather than an empty result that would read as
 *       "nothing is craftable". {@code freshness} names what is missing and the refresh work that
 *       was admitted.</li>
 * </ul>
 *
 * @param status    {@code CALCULATED} | {@code PENDING}
 * @param freshness the inputs this response was calculated from and the work outstanding on them
 * @param rowCount  number of rows, null when {@code PENDING}
 * @param rows      the rows, null when {@code PENDING} — never a fabricated empty list
 */
public record CraftingProfitResponse(String status,
                                     EffectiveScopeDto scope,
                                     EffectiveSettingsDto settings,
                                     Integer rowCount,
                                     List<CraftingRowDto> rows,
                                     CalculationFreshnessDto freshness) {

    /** A completed calculation over usable cached inputs. */
    public static final String STATUS_CALCULATED = "CALCULATED";

    /** No usable synchronized inputs existed; refresh was admitted and nothing was calculated. */
    public static final String STATUS_PENDING = "PENDING";

    /** The scope the calculation ran with, after defaults were applied. */
    public record EffectiveScopeDto(String kind, String discipline, String characterName, int rating) {
    }

    /** The settings the calculation ran with, after defaults were applied. */
    public record EffectiveSettingsDto(boolean useOwnMats,
                                       boolean allowBuying,
                                       int maxBuyCopper,
                                       boolean listingSell,
                                       boolean listingBuy,
                                       boolean allowDailyCrafts) {
    }
}
