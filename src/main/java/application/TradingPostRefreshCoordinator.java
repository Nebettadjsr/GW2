package application;

import java.util.Optional;

/**
 * Coordination for the shared Trading Post quote refresh a calculation needs
 * (TARGET_ARCHITECTURE.md §9.1), kept deliberately separate from
 * {@link AccountRefreshCoordinator}: the two mechanisms have different scopes, different caching
 * policies and different operation keys, and §9.1 requires them to stay distinguishable.
 *
 * <p>No feature-specific cache or fetch path is introduced. The required item set, the ten-minute
 * reuse policy and the batched upstream fetch all remain where they already are, behind
 * {@link TradingPostPriceRefreshService} and the collectors it delegates to, which Profit and
 * Discovery continue to share.
 *
 * <p>What this adds is demand coordination:
 *
 * <ul>
 *   <li><b>Coalescing.</b> Concurrent callers reporting a coverage gap join one run instead of
 *       starting competing fetches of overlapping item sets.</li>
 *   <li><b>Rechecking.</b> A gap reported while a run is in flight makes that run take one further
 *       pass when it finishes. Because the required set is recomputed from the database at the start
 *       of each pass, that pass covers exactly the ids still missing or stale — including ids that
 *       became relevant only because an account refresh changed recipe knowledge meanwhile.</li>
 *   <li><b>Bounded work.</b> One further pass per reported gap, never a self-feeding loop: an item
 *       the upstream API never answers for stays uncovered and keeps the result provisional rather
 *       than being refetched forever or declared authoritatively unavailable.</li>
 * </ul>
 */
public class TradingPostRefreshCoordinator {

    /** The pre-existing operation key for the Profit item set (see {@code web.PriceRefreshVariant}). */
    public static final String PROFIT_OPERATION = "PRICE_REFRESH_PROFIT";

    public static final String REUSE_ADMITTED = "ADMITTED";

    /** A run is already in flight and will take one more pass for this demand when it ends. */
    public static final String REUSE_COALESCED_INTO_ACTIVE_RUN = "COALESCED_INTO_ACTIVE_RUN";

    /** Another entry point holds the operation key; nothing was admitted and nothing was adopted. */
    public static final String REUSE_WAITING_FOR_UNRELATED_TASK = "WAITING_FOR_UNRELATED_TASK";

    private final TradingPostPriceRefreshService refresh;
    private final BackgroundTasks tasks;

    private boolean demanded;
    private boolean draining;
    private String activeTaskId;
    private int completedPasses;
    private CalculationFreshness.RefreshFailure lastFailure;

    public TradingPostRefreshCoordinator(TradingPostPriceRefreshService refresh, BackgroundTasks tasks) {
        this.refresh = refresh;
        this.tasks = tasks;
    }

    /** @param taskId the task to poll, null when none could be admitted or identified */
    public record Admission(String taskId, String reuse) {
    }

    /** Records that the Profit item set is not fully covered and makes sure a run is working on it. */
    public synchronized Admission requireProfitCoverage() {
        demanded = true;
        if (draining) {
            return new Admission(activeTaskId, REUSE_COALESCED_INTO_ACTIVE_RUN);
        }

        // Submitted under this monitor for the same reason as the account coordinator's.
        draining = true;
        try {
            activeTaskId = tasks.submit(PROFIT_OPERATION, this::drain);
            return new Admission(activeTaskId, REUSE_ADMITTED);

        } catch (BackgroundTasks.OperationAlreadyRunning foreign) {
            draining = false;
            activeTaskId = foreign.existingTaskId();
            return new Admission(foreign.existingTaskId(), REUSE_WAITING_FOR_UNRELATED_TASK);

        } catch (RuntimeException admissionFailure) {
            draining = false;
            throw admissionFailure;
        }
    }

    private void drain() throws Exception {
        try {
            while (true) {
                synchronized (this) {
                    if (!demanded) return;
                    demanded = false;
                }

                try {
                    refresh.refreshForProfit();
                    synchronized (this) {
                        completedPasses++;
                        lastFailure = null;
                    }
                } catch (Exception failure) {
                    synchronized (this) {
                        lastFailure = new CalculationFreshness.RefreshFailure(
                                CalculationFreshness.RefreshKind.TRADING_POST, activeTaskId,
                                "PRICE_REFRESH_FAILED",
                                "Trading Post price refresh failed; the previously fetched quotes are unchanged",
                                true);
                    }
                    throw failure;
                }
            }
        } finally {
            synchronized (this) {
                draining = false;
                activeTaskId = null;
            }
        }
    }

    /** How many fetch passes have completed successfully; the coalescing assertion reads this. */
    public synchronized int completedPasses() {
        return completedPasses;
    }

    public synchronized boolean refreshing() {
        return draining;
    }

    public synchronized Optional<CalculationFreshness.RefreshFailure> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }
}
