package web.dto;

import java.util.List;

/**
 * Transport form of the Crafting Profit freshness envelope (STORY-APP-014,
 * TARGET_ARCHITECTURE.md §9.1), carried beside the unchanged economic result.
 *
 * <p>Bounded on purpose. It reports per-source states, aggregate price coverage, the outstanding
 * work and at most one recovery item per refresh mechanism — never a per-item price list, a task
 * history or an input dump, so the body a browser polls against stays a fixed size no matter how
 * many items a calculation needed.
 *
 * <p>Nothing here is a result. {@code provisional} describes the inputs, not the correctness of the
 * numbers: a provisional result is the ordinary authoritative calculation over the last
 * successfully synchronized inputs, and the domain's own states ({@code PRICE_UNAVAILABLE} and the
 * rest) keep their existing meaning in the rows regardless of what this says.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 *
 * @param requestId       correlates the response with the request that produced it, so a late
 *                        answer cannot be mistaken for the current one
 * @param calculationId   identity of the input generation the calculation actually used; equal
 *                        across a row and a detail computed from the same generation
 * @param evaluatedAt     UTC ISO-8601 instant the backend took this freshness decision
 * @param provisional     true while the inputs are not confirmed current, or no result exists; a
 *                        succeeded refresh task alone never makes it false
 * @param resultAvailable false only on the pending response, which carries no rows
 * @param accountSources  one entry per account source this feature requires
 * @param tradingPost     coverage of the feature-relevant tradeable item set
 * @param outstandingWork refresh work known to be unfinished for these inputs; poll
 *                        {@code taskStatusUrl} and then request the calculation again
 * @param failures        bounded recovery information; an empty list means nothing has failed
 */
public record CalculationFreshnessDto(String requestId,
                                      String calculationId,
                                      String evaluatedAt,
                                      boolean provisional,
                                      boolean resultAvailable,
                                      List<AccountSourceDto> accountSources,
                                      PriceCoverageDto tradingPost,
                                      List<OutstandingRefreshDto> outstandingWork,
                                      List<RefreshFailureDto> failures) {

    /**
     * @param source    the source key the account-sync state records
     * @param fetchedAt UTC ISO-8601 instant of the source's last successful synchronization; null
     *                  only when {@code state} is {@code MISSING}
     * @param state     {@code FRESH} | {@code STALE} | {@code MISSING}. {@code STALE} still has
     *                  usable data behind it; {@code MISSING} never completed and is not invented.
     *                  A source that completed and held nothing is {@code FRESH}.
     */
    public record AccountSourceDto(String source, String fetchedAt, String state) {
    }

    /**
     * @param requiredItems  feature-relevant tradeable items this calculation needed quotes for
     * @param freshItems     of those, items whose stored quote is inside the shared ten-minute policy
     * @param staleItems     of those, items whose stored quote is older than it
     * @param missingItems   of those, items with no stored quote at all — never reported as covered,
     *                       because only a successful fetch may establish that an item has no market
     * @param oldestFetchedAt UTC ISO-8601 instant of the oldest stored quote in the required set
     * @param newestFetchedAt UTC ISO-8601 instant of the newest stored quote in the required set
     * @param covered        true only when nothing in the required set is stale or missing
     */
    public record PriceCoverageDto(int requiredItems,
                                   int freshItems,
                                   int staleItems,
                                   int missingItems,
                                   String oldestFetchedAt,
                                   String newestFetchedAt,
                                   boolean covered) {
    }

    /**
     * @param kind          {@code ACCOUNT} | {@code TRADING_POST}; the two are coordinated
     *                      separately and are never merged into one state
     * @param sources       the account sources this work is expected to refresh; empty for Trading
     *                      Post work, whose item set is recomputed when it runs
     * @param taskId        identifier for the existing task-status route, null when no task could
     *                      be admitted
     * @param taskState     state that route last reported, or {@code UNKNOWN}/{@code NONE}
     * @param taskStatusUrl path to poll, null when there is no task
     * @param reuse         how this work relates to work already running — including
     *                      {@code WAITING_FOR_UNRELATED_TASK}, which means the named task is
     *                      <em>not</em> refreshing these sources
     */
    public record OutstandingRefreshDto(String kind,
                                        List<String> sources,
                                        String taskId,
                                        String taskState,
                                        String taskStatusUrl,
                                        String reuse) {
    }

    /**
     * @param retryable whether requesting the calculation again can reasonably admit the work again
     */
    public record RefreshFailureDto(String kind,
                                    String taskId,
                                    String taskStatusUrl,
                                    String code,
                                    String message,
                                    boolean retryable) {
    }
}
