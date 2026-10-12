package application;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What a Crafting Profit response says about the inputs its calculation actually used
 * (TARGET_ARCHITECTURE.md §9.1's freshness envelope).
 *
 * <p>Application-owned and transport-free: no HTTP status, URL or task handle appears here, only the
 * task identifiers the status route already publishes. The economic result is untouched by it — this
 * type describes inputs and outstanding work, never a number the domain produced.
 *
 * <p>Three rules the envelope enforces by construction:
 *
 * <ul>
 *   <li>{@link #accountSources()} and {@link #tradingPost()} describe the versions captured
 *       <em>before</em> the calculation, never markers read afterwards.</li>
 *   <li>{@link #calculationId()} is derived from exactly those versions, so a detail response and a
 *       summary row computed from the same generation carry the same identifier and one computed
 *       from a different generation cannot be mistaken for it.</li>
 *   <li>{@link #provisional()} is false only when every required account source is
 *       {@link SourceState#FRESH}, the required Trading Post item set is covered, and a result was
 *       calculated from those inputs. A succeeded task is never sufficient on its own.</li>
 * </ul>
 *
 * @param requestId      correlates this response with the request that produced it
 * @param calculationId  stable identity of the captured input generation; equal for two captures of
 *                       the same versions, different as soon as any version changes
 * @param evaluatedAt    backend instant the freshness decision was taken
 * @param provisional    true while the inputs are not confirmed fresh/covered, or no result exists
 * @param resultAvailable false when no usable cached inputs existed and nothing was calculated
 * @param accountSources per-source state of the sources this feature requires
 * @param tradingPost    coverage of the feature-relevant tradeable item set
 * @param outstandingWork refresh work known to be unfinished for these inputs
 * @param failures       bounded, actionable recovery information for refresh work that failed or
 *                       whose task this process can no longer identify
 */
public record CalculationFreshness(String requestId,
                                   String calculationId,
                                   Instant evaluatedAt,
                                   boolean provisional,
                                   boolean resultAvailable,
                                   List<SourceFreshness> accountSources,
                                   PriceCoverage tradingPost,
                                   List<OutstandingRefresh> outstandingWork,
                                   List<RefreshFailure> failures) {

    /**
     * A required source's state relative to the configured account TTL.
     *
     * <p>{@link #MISSING} and {@link #STALE} are deliberately distinct: a source that has never
     * completed cannot be calculated from at all, while an expired one still has its last
     * successful data and is usable for a provisional result. A source that completed successfully
     * and happened to contain nothing is {@link #FRESH} — synchronized emptiness is data, not
     * absence.
     */
    public enum SourceState { FRESH, STALE, MISSING }

    /** Which independently coordinated refresh mechanism a piece of outstanding work belongs to. */
    public enum RefreshKind { ACCOUNT, TRADING_POST }

    /**
     * @param fetchedAt the source's last successful completion instant, null when {@code MISSING}
     */
    public record SourceFreshness(String source, Instant fetchedAt, SourceState state) {
    }

    /**
     * Coverage of the feature-relevant tradeable item set by the shared quote cache.
     *
     * <p>{@code missingItems} counts required items with no stored quote at all. An absent quote is
     * never reported as covered: only a successful fetch outcome may establish an item's price
     * unavailability, which the domain then expresses as its own explicit state.
     *
     * @param quoteVersion version of the relevant quotes the calculation actually read, null when
     *                     nothing was relevant. Two captures of the same stored quotes produce the
     *                     same value; any quote changing produces a different one, which the
     *                     aggregate counts and instants cannot show. It is part of
     *                     {@link #calculationId()} and is deliberately not published over HTTP: it
     *                     identifies a generation, it does not describe one.
     */
    public record PriceCoverage(int requiredItems,
                                int freshItems,
                                int staleItems,
                                int missingItems,
                                Instant oldestFetchedAt,
                                Instant newestFetchedAt,
                                boolean covered,
                                String quoteVersion) {

        public static PriceCoverage none() {
            return new PriceCoverage(0, 0, 0, 0, null, null, true, null);
        }
    }

    /**
     * @param sources   the sources this work is expected to refresh; empty for Trading Post work,
     *                  whose required item set is recomputed when the work runs
     * @param taskId    the identifier the existing task-status route reports, null when no task
     *                  could be admitted
     * @param taskState the state that route last reported, or {@code UNKNOWN}
     * @param reuse     how this work relates to work that was already running
     */
    public record OutstandingRefresh(RefreshKind kind,
                                     Set<String> sources,
                                     String taskId,
                                     String taskState,
                                     String reuse) {
    }

    /**
     * @param retryable whether repeating the request can reasonably admit the work again
     */
    public record RefreshFailure(RefreshKind kind,
                                 String taskId,
                                 String code,
                                 String message,
                                 boolean retryable) {
    }
}
