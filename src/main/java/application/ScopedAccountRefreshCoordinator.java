package application;

import api.Gw2ApiClient;
import application.access.AccountAccessService;
import application.access.AccountContext;
import application.access.AccountGrant;
import application.access.AccountOperation;
import application.access.InvalidCredentialException;
import application.access.MalformedProofException;
import application.access.ProofUnavailableException;
import model.AccountScope;
import repo.AccountSyncStateRepository;
import sync.AccountSync;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Account-keyed, credential-aware admission and execution of account refreshes (STORY-SYNC-006,
 * TARGET_ARCHITECTURE.md §7.2-7.4, §8.1, §9.1). It supersedes the process-wide
 * {@link AccountRefreshCoordinator} for callers that hold a trusted {@link AccountContext}.
 *
 * <ul>
 *   <li><b>Admission.</b> Every demand supplies a trusted context, the presented session id and a
 *       transient key. {@link AccountAccessService#authorizeKeyedOperation} revalidates identity and
 *       the grants of each demanded source <em>before</em> anything is admitted; a rejection throws
 *       {@link Rejected} and nothing is fetched, published or retained. There is no fallback to the
 *       deployment key or to another account's credential: the only credential a worker can use is
 *       the one admitted with its run.</li>
 *   <li><b>Exclusion.</b> At most one unfinished {@code ACCOUNT_SYNC} task per verified account in
 *       the process (exclusion key {@code ACCOUNT_SYNC:<guid>}, never reported); different accounts
 *       progress independently.</li>
 *   <li><b>Narrow to wide.</b> A further demand joins the active run only when the run's own
 *       validated credential covers those sources. Otherwise it is told to wait
 *       ({@link #REUSE_WAITING_FOR_ACTIVE_TASK}); its key is not retained, and after the run is
 *       terminal the caller rechecks freshness and is admitted with its own validated credential.
 *       Admission never claims success for a source that was not refreshed.</li>
 *   <li><b>Worker scope.</b> The run captures the {@link AccountScope} and the key at admission.
 *       Nothing is read from the session afterwards, so expiry, logout or a browser account
 *       replacement do not change what a running worker does.</li>
 *   <li><b>Secrecy.</b> The key lives only in a private {@link Credential} cell, is cleared on every
 *       terminal path, and appears in no status, record, log or error. Retained
 *       {@link TaskRecord}s hold the owner, required grants and sources only.</li>
 * </ul>
 */
public class ScopedAccountRefreshCoordinator {

    /** The reported operation; per-account exclusion uses {@code OPERATION + ":" + guid}. */
    public static final String OPERATION = AccountRefreshCoordinator.OPERATION;

    public static final String REUSE_ADMITTED = AccountRefreshCoordinator.REUSE_ADMITTED;
    public static final String REUSE_SUFFICIENT_SCOPE = AccountRefreshCoordinator.REUSE_SUFFICIENT_SCOPE;
    public static final String REUSE_QUEUED_BEHIND_ACTIVE_RUN = AccountRefreshCoordinator.REUSE_QUEUED_BEHIND_ACTIVE_RUN;

    /** The account's active run holds a credential that does not cover these sources; recheck later. */
    public static final String REUSE_WAITING_FOR_ACTIVE_TASK = "WAITING_FOR_ACTIVE_TASK";

    private static final int RETAINED_RECORDS = 100;

    /** Why admission was refused; carries no credential and no upstream text. */
    public enum Reason {
        SESSION, INVALID_CREDENTIAL, ACCOUNT_MISMATCH, INSUFFICIENT_GRANTS, UPSTREAM_UNAVAILABLE, MALFORMED_PROOF
    }

    /** Admission refused: nothing was fetched, published or retained. */
    public static final class Rejected extends RuntimeException {
        private final Reason reason;

        public Rejected(Reason reason) {
            super("account refresh not admitted: " + reason);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    public record Admission(String taskId, Set<String> sources, String reuse) {
        public Admission {
            sources = Set.copyOf(sources);
        }
    }

    /** Retained, credential-free ownership facts for a task, for later authorized observation. */
    public record TaskRecord(String taskId, String accountId, Set<AccountGrant> requiredGrants,
                             Set<String> sources) {
        public TaskRecord {
            requiredGrants = Set.copyOf(requiredGrants);
            sources = Set.copyOf(sources);
        }
    }

    /** Holds a transient key; {@link #clear()} is called on every terminal path. */
    static final class Credential {
        private volatile String key;
        private volatile Runnable revoker;

        Credential(String key, Runnable revoker) {
            this.key = key;
            this.revoker = revoker;
        }

        /**
         * A definitive rejection is classified where it happens, so it is seen even when another
         * source failed first and the rejection only survives as a suppressed failure. It revokes
         * only the originating session; ordinary outages and malformed payloads do not.
         */
        AccountSync.AuthFetcher fetcher(Function<String, AccountSync.AuthFetcher> factory,
                                        Predicate<Throwable> rejection) {
            return url -> {
                String current = key;
                if (current == null) throw new IllegalStateException("the operation credential has been released");
                try {
                    return factory.apply(current).get(url);
                } catch (Exception | Error failure) {
                    if (rejection.test(failure)) {
                        Runnable revoke = revoker;
                        if (revoke != null) revoke.run();
                    }
                    throw failure;
                }
            };
        }

        void clear() {
            key = null;
            revoker = null;
        }

        boolean cleared() {
            return key == null;
        }
    }

    /**
     * The only failure that leaves a worker: fixed text, no cause, no suppressed exceptions, so the
     * task facility's log line and retained failure cannot disclose upstream text or credentials.
     */
    static class SanitizedRefreshFailure extends Exception {
        SanitizedRefreshFailure() {
            super("Account data refresh failed; upstream details are withheld", null, false, true);
        }
    }

    /**
     * Same disclosure rules, kept an {@link java.sql.SQLException} so the task facility's existing
     * data-store classification of the retained failure still applies.
     */
    static final class SanitizedDataStoreFailure extends java.sql.SQLException {
        SanitizedDataStoreFailure() {
            super("Account data refresh failed; data store details are withheld");
        }
    }

    private static Exception sanitize(Exception raw) {
        for (Throwable t = raw; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException) return new SanitizedDataStoreFailure();
        }
        return new SanitizedRefreshFailure();
    }

    /** Per-account coordination state; guarded by the coordinator's monitor. */
    private static final class Run {
        final Set<String> outstanding = new LinkedHashSet<>();
        final Set<String> refreshing = new LinkedHashSet<>();
        final Set<String> covered = new LinkedHashSet<>();
        Credential credential;
        String taskId;
        CalculationFreshness.RefreshFailure lastFailure;
        Set<String> lastRefreshed = Set.of();
    }

    private final AccountRefreshService refresh;
    private final BackgroundTasks tasks;
    private final AccountAccessService access;
    private final Function<String, AccountSync.AuthFetcher> fetcherFactory;
    private final Predicate<Throwable> rejection;

    private final Map<UUID, Run> active = new HashMap<>();
    private final Map<UUID, Run> history = new HashMap<>();
    private final Deque<TaskRecord> records = new ArrayDeque<>();

    public ScopedAccountRefreshCoordinator(AccountRefreshService refresh, BackgroundTasks tasks,
                                           AccountAccessService access,
                                           Function<String, AccountSync.AuthFetcher> fetcherFactory) {
        this(refresh, tasks, access, fetcherFactory, Gw2ApiClient::isAuthRejection);
    }

    /** {@code rejection} classifies a definitive "this key is invalid" upstream answer. */
    public ScopedAccountRefreshCoordinator(AccountRefreshService refresh, BackgroundTasks tasks,
                                           AccountAccessService access,
                                           Function<String, AccountSync.AuthFetcher> fetcherFactory,
                                           Predicate<Throwable> rejection) {
        this.rejection = rejection;
        this.refresh = refresh;
        this.tasks = tasks;
        this.access = access;
        this.fetcherFactory = fetcherFactory;
    }

    /** The grant-bearing operation that reads one source; null for an unknown source. */
    public static AccountOperation operationFor(String source) {
        return switch (source) {
            case AccountSyncStateRepository.BANK -> AccountOperation.BANK_READ;
            case AccountSyncStateRepository.MATERIALS -> AccountOperation.MATERIAL_STORAGE_READ;
            case AccountSyncStateRepository.RECIPES -> AccountOperation.RECIPES_READ;
            case AccountSyncStateRepository.LUCK -> AccountOperation.LUCK_READ;
            case AccountSyncStateRepository.CHARACTERS -> AccountOperation.CHARACTERS_READ;
            default -> null;
        };
    }

    /**
     * Revalidates, then records the demand and makes sure the account's single writer works on it.
     * Returns immediately.
     *
     * @throws Rejected when identity or any demanded source's grants cannot be proven
     */
    public Admission require(AccountContext context, String sessionId, String apiKey, Set<String> sources) {
        return require(context, sessionId, apiKey, sources, null);
    }

    /**
     * As {@link #require(AccountContext, String, String, Set)}, reusing {@code proof} - obtained by
     * {@link #verify} earlier in the same request - instead of asking GW2 to validate the key again.
     * Session liveness, account match and every source's grants are still checked.
     */
    public Admission require(AccountContext context, String sessionId, String apiKey, Set<String> sources,
                             AccountAccessService.KeyedProof proof) {
        if (sources.isEmpty()) return new Admission(null, Set.of(), REUSE_ADMITTED);
        AccountScope scope = AccountScope.of(context.accountId());
        Set<AccountGrant> grants = EnumSet.noneOf(AccountGrant.class);
        authorize(context, sessionId, apiKey, proof, sources);
        for (String source : sources) grants.addAll(operationFor(source).requiredGrants());

        synchronized (this) {
            Run run = active.get(scope.accountId());
            if (run != null) {
                if (!run.covered.containsAll(sources)) {
                    return new Admission(run.taskId, sources, REUSE_WAITING_FOR_ACTIVE_TASK);
                }
                boolean inFlight = run.refreshing.containsAll(sources);
                run.outstanding.addAll(sources);
                remember(run.taskId, scope, grants, sources);
                return new Admission(run.taskId, sources,
                        inFlight ? REUSE_SUFFICIENT_SCOPE : REUSE_QUEUED_BEHIND_ACTIVE_RUN);
            }

            Run fresh = new Run();
            fresh.credential = new Credential(apiKey, access.revocationOf(sessionId));
            fresh.covered.addAll(sources);
            fresh.outstanding.addAll(sources);
            active.put(scope.accountId(), fresh);
            try {
                fresh.taskId = tasks.submit(OPERATION, OPERATION + ":" + scope.id(), () -> drain(scope, fresh));
            } catch (BackgroundTasks.OperationAlreadyRunning foreign) {
                // Another entry point holds this account's exclusion; keep nothing.
                active.remove(scope.accountId());
                fresh.credential.clear();
                return new Admission(foreign.existingTaskId(), sources, REUSE_WAITING_FOR_ACTIVE_TASK);
            } catch (RuntimeException failure) {
                active.remove(scope.accountId());
                fresh.credential.clear();
                throw failure;
            }
            remember(fresh.taskId, scope, grants, sources);
            return new Admission(fresh.taskId, sources, REUSE_ADMITTED);
        }
    }

    /**
     * Proves the key against the session's account for every given source's operation without
     * admitting anything, so a caller can reject before it does account work or records activity.
     *
     * @throws Rejected when identity or any source's grants cannot be proven
     */
    public AccountAccessService.KeyedProof verify(AccountContext context, String sessionId, String apiKey,
                                                   Set<String> sources) {
        return authorize(context, sessionId, apiKey, null, sources);
    }

    /** One upstream proof for the whole source set (or the supplied one), then a check per source. */
    private AccountAccessService.KeyedProof authorize(AccountContext context, String sessionId, String apiKey,
                                                      AccountAccessService.KeyedProof proof, Set<String> sources) {
        java.util.List<AccountOperation> operations = new java.util.ArrayList<>();
        for (String source : sources) {
            AccountOperation operation = operationFor(source);
            if (operation == null) throw new IllegalArgumentException("unknown account source: " + source);
            operations.add(operation);
        }
        try {
            if (proof != null) {
                access.authorizeKeyedOperations(sessionId, context, proof, operations);
                return proof;
            }
            return access.authorizeKeyedOperations(sessionId, context, apiKey, operations);
        } catch (InvalidCredentialException e) {
            throw new Rejected(Reason.INVALID_CREDENTIAL);
        } catch (AccountAccessService.AccountMismatchException e) {
            throw new Rejected(Reason.ACCOUNT_MISMATCH);
        } catch (AccountAccessService.InsufficientGrantsException e) {
            throw new Rejected(Reason.INSUFFICIENT_GRANTS);
        } catch (ProofUnavailableException e) {
            throw new Rejected(Reason.UPSTREAM_UNAVAILABLE);
        } catch (MalformedProofException e) {
            throw new Rejected(Reason.MALFORMED_PROOF);
        } catch (IllegalStateException e) {
            throw new Rejected(Reason.SESSION);
        }
    }

    /**
     * A full sync that arrives behind a narrower active run is told to wait
     * ({@link #REUSE_WAITING_FOR_ACTIVE_TASK}) and its key is not retained. This rechecks for up to
     * {@code bound}: once the active run is terminal the demand is admitted with the caller's own
     * (already proved) credential, so the uncovered sources - Luck in particular - are really
     * admitted. If the bound passes first, the waiting admission is returned unchanged and the
     * caller must say so honestly (it covers none of the uncovered sources).
     */
    public Admission requireAfterBoundedWait(AccountContext context, String sessionId, String apiKey,
                                             Set<String> sources, java.time.Duration bound) {
        AccountAccessService.KeyedProof proof = verify(context, sessionId, apiKey, sources);
        long deadline = System.nanoTime() + bound.toNanos();
        while (true) {
            Admission admission = require(context, sessionId, apiKey, sources, proof);
            if (!REUSE_WAITING_FOR_ACTIVE_TASK.equals(admission.reuse()) || admission.taskId() == null) {
                return admission;
            }
            while (tasks.find(admission.taskId()).map(status -> !status.terminal()).orElse(false)) {
                if (System.nanoTime() >= deadline) return admission;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return admission;
                }
            }
            if (System.nanoTime() >= deadline) return admission;
        }
    }

    private void remember(String taskId, AccountScope scope, Set<AccountGrant> grants, Set<String> sources) {
        if (taskId == null) return;
        Set<AccountGrant> mergedGrants = EnumSet.noneOf(AccountGrant.class);
        mergedGrants.addAll(grants);
        Set<String> mergedSources = new LinkedHashSet<>(sources);
        for (TaskRecord existing : records) {
            if (existing.taskId().equals(taskId)) {
                mergedGrants.addAll(existing.requiredGrants());
                mergedSources.addAll(existing.sources());
            }
        }
        records.removeIf(r -> r.taskId().equals(taskId));
        records.addLast(new TaskRecord(taskId, scope.id(), mergedGrants, mergedSources));
        pruneTerminalRecords();
    }

    /** Active ownership must outlive any history cap, just as active tasks do in the facility. */
    private void pruneTerminalRecords() {
        int terminal = 0;
        for (TaskRecord record : records) {
            if (tasks.find(record.taskId()).map(BackgroundTasks.Status::terminal).orElse(true)) terminal++;
        }
        var oldest = records.iterator();
        while (terminal > RETAINED_RECORDS && oldest.hasNext()) {
            TaskRecord record = oldest.next();
            if (tasks.find(record.taskId()).map(BackgroundTasks.Status::terminal).orElse(true)) {
                oldest.remove();
                UUID owner = UUID.fromString(record.accountId());
                Run retained = history.get(owner);
                if (retained != null && record.taskId().equals(retained.taskId)) history.remove(owner);
                terminal--;
            }
        }
    }

    /** The draining run; everything it needs was captured at admission. */
    private void drain(AccountScope scope, Run run) throws Exception {
        AccountSync.AuthFetcher fetcher = run.credential.fetcher(fetcherFactory, rejection);
        try {
            while (true) {
                Set<String> batch;
                synchronized (this) {
                    if (run.outstanding.isEmpty()) {
                        // Ending and no longer accepting joiners is one atomic step.
                        finishLocked(scope, run);
                        return;
                    }
                    batch = new LinkedHashSet<>(run.outstanding);
                    run.refreshing.clear();
                    run.refreshing.addAll(batch);
                }
                try {
                    refresh.refreshSources(scope, batch, fetcher);
                    synchronized (this) {
                        run.lastRefreshed = Set.copyOf(batch);
                        run.lastFailure = null;
                    }
                } catch (Exception raw) {
                    // The raw exception can carry upstream response text (malformed JSON snippets,
                    // error payloads) and arbitrary causes. Nothing from it may reach the task
                    // facility, which logs and retains whatever the body throws.
                    Exception failure = sanitize(raw);
                    synchronized (this) {
                        run.lastFailure = new CalculationFreshness.RefreshFailure(
                                CalculationFreshness.RefreshKind.ACCOUNT, run.taskId,
                                "ACCOUNT_REFRESH_FAILED",
                                "Account data refresh failed; the previously synchronized data is unchanged",
                                true);
                    }
                    throw failure;
                } finally {
                    synchronized (this) {
                        run.outstanding.removeAll(batch);
                        run.refreshing.clear();
                    }
                }
            }
        } finally {
            synchronized (this) {
                finishLocked(scope, run);
            }
        }
    }

    private void finishLocked(AccountScope scope, Run run) {
        run.credential.clear();
        run.outstanding.clear();
        run.refreshing.clear();
        if (active.get(scope.accountId()) == run) {
            active.remove(scope.accountId());
            history.put(scope.accountId(), run);
        }
    }

    /**
     * The credential-free record for {@code taskId}, only when {@code context} belongs to the same
     * account and covers the grants the task required; empty otherwise (not distinguishable from
     * unknown).
     */
    public synchronized Optional<TaskRecord> observe(AccountContext context, String taskId) {
        pruneTerminalRecords();
        for (TaskRecord record : records) {
            if (record.taskId().equals(taskId) && record.accountId().equalsIgnoreCase(context.accountId())
                    && context.grants().containsAll(record.requiredGrants())
                    && permitsEverySource(context, record.sources())) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /** Grant names alone are not enough: endpoint restrictions of the observer apply per source. */
    private static boolean permitsEverySource(AccountContext context, Set<String> sources) {
        for (String source : sources) {
            AccountOperation operation = operationFor(source);
            if (operation == null || !context.permits(operation)) return false;
        }
        return true;
    }

    public synchronized Set<String> outstandingSources(AccountScope scope) {
        Run run = active.get(scope.accountId());
        return run == null ? Set.of() : Set.copyOf(run.outstanding);
    }

    public synchronized Set<String> lastRefreshedSources(AccountScope scope) {
        Run run = active.containsKey(scope.accountId()) ? active.get(scope.accountId()) : history.get(scope.accountId());
        return run == null ? Set.of() : run.lastRefreshed;
    }

    public synchronized Optional<CalculationFreshness.RefreshFailure> lastFailure(AccountScope scope) {
        Run run = history.get(scope.accountId());
        return run == null ? Optional.empty() : Optional.ofNullable(run.lastFailure);
    }

    /** True while the account has an unfinished run this coordinator admitted. */
    public synchronized boolean isActive(AccountScope scope) {
        return active.containsKey(scope.accountId());
    }
}
