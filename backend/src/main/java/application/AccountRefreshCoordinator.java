package application;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Single-writer coordination for the account sources a calculation needs refreshed
 * (TARGET_ARCHITECTURE.md §9.1).
 *
 * <p>It exists because the previous admission — {@code submitOrExisting("ACCOUNT_SYNC", …)} — hands
 * a caller the identifier of whatever account task happens to be unfinished, no matter how narrow
 * its scope. A {@code /api/crafting/selector-options} request that started a {@code CHARACTERS}-only
 * sync would be reported to a Crafting Profit request as the refresh of all four of its sources; the
 * other three were never fetched, so the next request was stale again
 * (STORY-PERF-003 F002, docs/bugs/BUG-2026-001).
 *
 * <p>What replaces it:
 *
 * <ul>
 *   <li><b>Accumulated demand.</b> Every caller's required sources are added to one outstanding set.
 *       A single draining task refreshes that set in batches until it is empty, so sources demanded
 *       while a narrower batch is in flight are refreshed by the same run rather than forgotten —
 *       and because that run is one task executing one batch at a time, no two account writes
 *       overlap.</li>
 *   <li><b>No borrowed success.</b> Admission never claims a source was refreshed. Freshness is read
 *       from the persisted completion markers alone, so a reused task of insufficient scope leaves
 *       its omitted sources stale in the envelope. {@link Admission#reuse()} records which case
 *       applied, for diagnosis rather than for a freshness decision.</li>
 *   <li><b>Foreign tasks are waited out, not adopted.</b> When another entry point (the account-sync
 *       route, or the JavaFX app) holds the operation key, this coordinator keeps the demand and
 *       reports that task as outstanding without starting a concurrent writer. The next request
 *       rechecks and admits whatever is still required once that task is terminal.</li>
 * </ul>
 *
 * <p>Thread-safe: the outstanding set and the drain state are read and written only under this
 * instance's monitor; refresh work itself runs outside it.
 */
public class AccountRefreshCoordinator {

    /** The pre-existing operation key, deliberately unchanged so the single-writer rule still binds. */
    public static final String OPERATION = "ACCOUNT_SYNC";

    /** Work was admitted as a new draining task. */
    public static final String REUSE_ADMITTED = "ADMITTED";

    /** A run this coordinator owns is already refreshing every source the caller required. */
    public static final String REUSE_SUFFICIENT_SCOPE = "REUSED_SUFFICIENT_SCOPE";

    /** A run this coordinator owns is active but narrower; it will pick the rest up before ending. */
    public static final String REUSE_QUEUED_BEHIND_ACTIVE_RUN = "QUEUED_BEHIND_ACTIVE_RUN";

    /** Another entry point holds the operation key; nothing was admitted and nothing was adopted. */
    public static final String REUSE_WAITING_FOR_UNRELATED_TASK = "WAITING_FOR_UNRELATED_TASK";

    private final AccountRefreshService refresh;
    private final BackgroundTasks tasks;

    /** Sources demanded and not yet attempted by a run this coordinator owns. Guarded by {@code this}. */
    private final Set<String> outstanding = new LinkedHashSet<>();

    /** The batch the active run is refreshing right now. Guarded by {@code this}. */
    private final Set<String> refreshing = new LinkedHashSet<>();

    /** Sources the most recent successful batch actually refreshed. Guarded by {@code this}. */
    private final Set<String> lastRefreshed = new LinkedHashSet<>();

    private boolean draining;
    private String activeTaskId;
    private CalculationFreshness.RefreshFailure lastFailure;

    public AccountRefreshCoordinator(AccountRefreshService refresh, BackgroundTasks tasks) {
        this.refresh = refresh;
        this.tasks = tasks;
    }

    /**
     * What one caller's demand produced.
     *
     * @param taskId  the task to poll, null when none could be admitted or identified
     * @param sources the sources this caller required and that are still unrefreshed
     * @param reuse   one of the {@code REUSE_*} constants
     */
    public record Admission(String taskId, Set<String> sources, String reuse) {

        public Admission {
            sources = Set.copyOf(sources);
        }
    }

    /**
     * Records that {@code sources} need refreshing and makes sure a single writer is working on
     * them. Returns immediately; nothing here waits for upstream work.
     */
    public synchronized Admission require(Set<String> sources) {
        if (sources.isEmpty()) return new Admission(null, Set.of(), REUSE_ADMITTED);

        outstanding.addAll(sources);
        if (draining) {
            return new Admission(activeTaskId, sources,
                    refreshing.containsAll(sources)
                            ? REUSE_SUFFICIENT_SCOPE
                            : REUSE_QUEUED_BEHIND_ACTIVE_RUN);
        }

        // Submitted while holding this monitor on purpose: the body's first act is to take the same
        // monitor, so it cannot start draining before the identifier it will report is published.
        draining = true;
        try {
            activeTaskId = tasks.submit(OPERATION, this::drain);
            return new Admission(activeTaskId, sources, REUSE_ADMITTED);

        } catch (BackgroundTasks.OperationAlreadyRunning foreign) {
            // Someone else is the single account writer for now. Keep the demand - the next request
            // rechecks it - and do not pretend that task covers these sources.
            draining = false;
            activeTaskId = foreign.existingTaskId();
            return new Admission(foreign.existingTaskId(), sources, REUSE_WAITING_FOR_UNRELATED_TASK);

        } catch (RuntimeException admissionFailure) {
            draining = false;
            throw admissionFailure;
        }
    }

    /**
     * The draining run: refresh the outstanding batch, then look again, because a caller needing
     * more sources may have arrived while the previous batch was in flight. A failing batch ends the
     * run (its sources keep their previous markers and last-good data) instead of retrying in a loop.
     */
    private void drain() throws Exception {
        try {
            while (true) {
                Set<String> batch;
                synchronized (this) {
                    if (outstanding.isEmpty()) return;
                    batch = new LinkedHashSet<>(outstanding);
                    refreshing.clear();
                    refreshing.addAll(batch);
                }

                try {
                    refresh.refreshSources(batch);
                    synchronized (this) {
                        lastRefreshed.clear();
                        lastRefreshed.addAll(batch);
                        lastFailure = null;
                    }
                } catch (Exception failure) {
                    synchronized (this) {
                        lastFailure = new CalculationFreshness.RefreshFailure(
                                CalculationFreshness.RefreshKind.ACCOUNT, activeTaskId,
                                "ACCOUNT_REFRESH_FAILED",
                                "Account data refresh failed; the previously synchronized data is unchanged",
                                true);
                    }
                    throw failure;
                } finally {
                    synchronized (this) {
                        outstanding.removeAll(batch);
                        refreshing.clear();
                    }
                }
            }
        } finally {
            synchronized (this) {
                draining = false;
                activeTaskId = null;
            }
        }
    }

    /** Sources demanded and not yet attempted by a run this coordinator owns. */
    public synchronized Set<String> outstandingSources() {
        return Set.copyOf(outstanding);
    }

    /** The batch currently being refreshed, empty when no run this coordinator owns is active. */
    public synchronized Set<String> refreshingSources() {
        return Set.copyOf(refreshing);
    }

    /** What the most recent successful batch refreshed — the sources, not merely a task identifier. */
    public synchronized Set<String> lastRefreshedSources() {
        return Set.copyOf(lastRefreshed);
    }

    /** The most recent failed run, until a later run succeeds. */
    public synchronized Optional<CalculationFreshness.RefreshFailure> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }
}
