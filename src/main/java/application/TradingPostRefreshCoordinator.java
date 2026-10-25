package application;

import java.util.EnumMap;
import java.util.Map;
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
 *   <li><b>One entry path.</b> Every demand for shared quotes - the cached Profit calculation, the
 *       manual {@code POST /api/prices/refresh} for either {@link Variant}, and any later caller -
 *       is admitted here. Each variant has its own task key (as before) and its own coalescing
 *       state, while the upstream work of both goes through the one shared
 *       {@link TradingPostPriceRefreshService} instance, whose methods exclude each other. A run
 *       that waits behind the other variant therefore recomputes its required ids from the shared
 *       {@code tp_prices} quotes afterwards and fetches only what is still missing or older than
 *       ten minutes: overlapping ids are fetched once, and ids that only the second feature needs
 *       are still covered.</li>
 *   <li><b>Bounded work.</b> One further pass per reported gap, never a self-feeding loop: an item
 *       the upstream API never answers for stays uncovered and keeps the result provisional rather
 *       than being refetched forever or declared authoritatively unavailable.</li>
 * </ul>
 */
public class TradingPostRefreshCoordinator {

    /** The two requestable item sets; each keeps the task key it has always been reported under. */
    public enum Variant {
        PROFIT("PRICE_REFRESH_PROFIT"),
        DISCOVERY("PRICE_REFRESH_DISCOVERY");

        private final String operation;

        Variant(String operation) {
            this.operation = operation;
        }

        public String operation() {
            return operation;
        }
    }

    /** The pre-existing operation key for the Profit item set (see {@code web.PriceRefreshVariant}). */
    public static final String PROFIT_OPERATION = Variant.PROFIT.operation();

    public static final String REUSE_ADMITTED = "ADMITTED";

    /** A run is already in flight and will take one more pass for this demand when it ends. */
    public static final String REUSE_COALESCED_INTO_ACTIVE_RUN = "COALESCED_INTO_ACTIVE_RUN";

    /** Another entry point holds the operation key; the demand is kept and nothing was adopted. */
    public static final String REUSE_WAITING_FOR_UNRELATED_TASK = "WAITING_FOR_UNRELATED_TASK";

    /** Coordination state of one variant; guarded by the coordinator's monitor. */
    private static final class State {
        boolean demanded;
        boolean draining;
        String activeTaskId;
        int completedPasses;
        CalculationFreshness.RefreshFailure lastFailure;
    }

    private final TradingPostPriceRefreshService refresh;
    private final BackgroundTasks tasks;
    private final Map<Variant, State> states = new EnumMap<>(Variant.class);

    public TradingPostRefreshCoordinator(TradingPostPriceRefreshService refresh, BackgroundTasks tasks) {
        this.refresh = refresh;
        this.tasks = tasks;
        for (Variant variant : Variant.values()) states.put(variant, new State());
    }

    /** @param taskId the task to poll, null when none could be admitted or identified */
    public record Admission(String taskId, String reuse) {
    }

    /** Records that the Profit item set is not fully covered and makes sure a run is working on it. */
    public Admission requireProfitCoverage() {
        return require(Variant.PROFIT);
    }

    /**
     * Records that {@code variant}'s item set is not fully covered and makes sure a run is working
     * on it. A demand raised while that variant's run is in flight earns exactly one recheck pass.
     */
    public synchronized Admission require(Variant variant) {
        State state = states.get(variant);
        state.demanded = true;
        if (state.draining) {
            return new Admission(state.activeTaskId, REUSE_COALESCED_INTO_ACTIVE_RUN);
        }

        // Submitted under this monitor for the same reason as the account coordinator's.
        state.draining = true;
        try {
            state.activeTaskId = tasks.submit(variant.operation(), () -> drain(variant, state));
            return new Admission(state.activeTaskId, REUSE_ADMITTED);

        } catch (BackgroundTasks.OperationAlreadyRunning foreign) {
            state.draining = false;
            state.activeTaskId = foreign.existingTaskId();
            return new Admission(foreign.existingTaskId(), REUSE_WAITING_FOR_UNRELATED_TASK);

        } catch (RuntimeException admissionFailure) {
            state.draining = false;
            throw admissionFailure;
        }
    }

    /**
     * Brings the variant's quotes up to date before a calculation reads them, on the caller's
     * thread, and returns only when that has completed (or throws the failure unchanged). This is
     * the entry for calculations that must keep their synchronous result/error contract: they may
     * not be answered from missing or stale quotes, so the demand cannot merely be scheduled.
     *
     * <p>It still goes through the one coordinator: the upstream work is the shared service's,
     * whose methods exclude every other run (manual, scheduled-by-demand or another calculation),
     * so a call arriving behind another run recomputes its required ids afterwards and fetches only
     * what is still missing or older than ten minutes. A manual run of the same variant that is in
     * flight is told to take one recheck pass, so ids this demand adds are covered there too.
     */
    public void refreshAndAwait(Variant variant) throws Exception {
        State state = states.get(variant);
        synchronized (this) {
            if (state.draining) state.demanded = true;
        }
        try {
            refreshFor(variant);
        } catch (Exception failure) {
            synchronized (this) {
                state.lastFailure = new CalculationFreshness.RefreshFailure(
                        CalculationFreshness.RefreshKind.TRADING_POST, null,
                        "PRICE_REFRESH_FAILED",
                        "Trading Post price refresh failed; the previously fetched quotes are unchanged",
                        true);
            }
            throw failure;
        }
        synchronized (this) {
            state.completedPasses++;
            state.lastFailure = null;
        }
    }

    private void refreshFor(Variant variant) throws Exception {
        switch (variant) {
            case PROFIT -> refresh.refreshForProfit();
            case DISCOVERY -> refresh.refreshForDiscovery();
        }
    }

    private void drain(Variant variant, State state) throws Exception {
        try {
            while (true) {
                synchronized (this) {
                    if (!state.demanded) return;
                    state.demanded = false;
                }

                try {
                    refreshFor(variant);
                    synchronized (this) {
                        state.completedPasses++;
                        state.lastFailure = null;
                    }
                } catch (Exception failure) {
                    synchronized (this) {
                        state.lastFailure = new CalculationFreshness.RefreshFailure(
                                CalculationFreshness.RefreshKind.TRADING_POST, state.activeTaskId,
                                "PRICE_REFRESH_FAILED",
                                "Trading Post price refresh failed; the previously fetched quotes are unchanged",
                                true);
                    }
                    throw failure;
                }
            }
        } finally {
            synchronized (this) {
                state.draining = false;
                state.activeTaskId = null;
            }
        }
    }

    /** How many Profit fetch passes have completed successfully; the coalescing assertion reads this. */
    public synchronized int completedPasses() {
        return completedPasses(Variant.PROFIT);
    }

    public synchronized int completedPasses(Variant variant) {
        return states.get(variant).completedPasses;
    }

    public synchronized boolean refreshing() {
        return states.get(Variant.PROFIT).draining;
    }

    public synchronized boolean refreshing(Variant variant) {
        return states.get(variant).draining;
    }

    public synchronized Optional<CalculationFreshness.RefreshFailure> lastFailure() {
        return lastFailure(Variant.PROFIT);
    }

    public synchronized Optional<CalculationFreshness.RefreshFailure> lastFailure(Variant variant) {
        return Optional.ofNullable(states.get(variant).lastFailure);
    }
}
