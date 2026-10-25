package application;

import application.access.AccountAccessService;
import application.access.AccountContext;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountSessions;
import application.access.InvalidCredentialException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import model.AccountScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import repo.AccountSyncStateRepository;
import sync.AccountRefreshGateway;
import sync.AccountSync;
import web.BackgroundTaskServiceTasks;
import web.task.BackgroundTaskService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** STORY-SYNC-006 against the real in-process task facility, with latches and an offline gateway. */
class ScopedAccountRefreshCoordinatorTest {

    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final String B = "22222222-2222-2222-2222-222222222222";
    private static final Set<AccountGrant> WIDE = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES,
            AccountGrant.CHARACTERS);
    private static final Set<AccountGrant> NARROW = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES);

    private final BackgroundTaskService service = new BackgroundTaskService();
    private final AccountSessions sessions = new AccountSessions(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), 8);
    /** key -> proof; a key absent from the map is rejected as invalid. */
    private final Map<String, AccountProof> keys = new ConcurrentHashMap<>();
    private final Gateway gateway = new Gateway();
    private final java.util.concurrent.atomic.AtomicInteger fetcherRequests = new java.util.concurrent.atomic.AtomicInteger();
    private final ScopedAccountRefreshCoordinator coordinator = new ScopedAccountRefreshCoordinator(
            new AccountRefreshService(gateway), new BackgroundTaskServiceTasks(service),
            new AccountAccessService(key -> {
                AccountProof proof = keys.get(key);
                if (proof == null) throw new InvalidCredentialException("rejected");
                return proof;
            }, sessions),
            key -> url -> {
                fetcherRequests.incrementAndGet();
                return (JsonNode) TextNode.valueOf(key);
            });

    @AfterEach
    void close() {
        gateway.release.countDown();
        service.close();
    }

    private AccountSessions.Issued session(String account, String key, Set<AccountGrant> grants) {
        AccountProof proof = new AccountProof(account, grants, Set.of(), null);
        keys.put(key, proof);
        return sessions.establish(proof);
    }

    private ScopedAccountRefreshCoordinator.Admission admit(AccountSessions.Issued s, String key, String... sources) {
        return coordinator.require(s.context(), s.sessionId(), key, Set.of(sources));
    }

    @Test
    void rejectsInvalidMismatchedAndInsufficientCredentialsWithoutAdmissionOrFetch() {
        AccountSessions.Issued narrow = session(A, "narrow-key", NARROW);
        AccountSessions.Issued mismatchSession = session(A, "narrow-key-m", NARROW);
        keys.put("other-account", new AccountProof(B, NARROW, Set.of(), null));
        keys.put("weak", new AccountProof(A, Set.of(AccountGrant.ACCOUNT), Set.of(), null));

        assertEquals(ScopedAccountRefreshCoordinator.Reason.INVALID_CREDENTIAL,
                assertThrows(ScopedAccountRefreshCoordinator.Rejected.class,
                        () -> admit(narrow, "unknown", AccountSyncStateRepository.BANK)).reason());
        assertEquals(ScopedAccountRefreshCoordinator.Reason.ACCOUNT_MISMATCH,
                assertThrows(ScopedAccountRefreshCoordinator.Rejected.class,
                        () -> admit(mismatchSession, "other-account", AccountSyncStateRepository.BANK)).reason());
        // the session itself is revoked by the invalid key, so use a fresh one for the grant checks
        AccountSessions.Issued fresh = session(A, "narrow-key-2", NARROW);
        assertEquals(ScopedAccountRefreshCoordinator.Reason.INSUFFICIENT_GRANTS,
                assertThrows(ScopedAccountRefreshCoordinator.Rejected.class,
                        () -> admit(fresh, "narrow-key-2", AccountSyncStateRepository.CHARACTERS)).reason());
        AccountSessions.Issued weakSession = session(A, "weak-session", Set.of(AccountGrant.ACCOUNT));
        assertEquals(ScopedAccountRefreshCoordinator.Reason.INSUFFICIENT_GRANTS,
                assertThrows(ScopedAccountRefreshCoordinator.Rejected.class,
                        () -> admit(weakSession, "weak", AccountSyncStateRepository.BANK)).reason());

        assertTrue(gateway.calls.isEmpty());
        assertFalse(coordinator.isActive(AccountScope.of(A)));
    }

    @Test
    void sameAccountIsExclusiveWhileDifferentAccountsProgressAndNarrowDemandJoinsOnlyCoveredSources()
            throws Exception {
        AccountSessions.Issued a = session(A, "key-a", NARROW);
        AccountSessions.Issued b = session(B, "key-b", WIDE);
        gateway.blockAccount = A;

        var first = admit(a, "key-a", AccountSyncStateRepository.BANK);
        assertEquals(ScopedAccountRefreshCoordinator.REUSE_ADMITTED, first.reuse());
        assertTrue(gateway.started.await(5, TimeUnit.SECONDS));

        // Different account is not blocked by A's unfinished task.
        var other = admit(b, "key-b", AccountSyncStateRepository.CHARACTERS);
        awaitTerminal(other.taskId());
        assertEquals(List.of("B:CHARACTERS:key-b"), gateway.callsFor(B));

        // Same account, source covered by the active credential: joins the same task.
        var joined = admit(a, "key-a", AccountSyncStateRepository.BANK);
        assertEquals(first.taskId(), joined.taskId());
        assertEquals(ScopedAccountRefreshCoordinator.REUSE_SUFFICIENT_SCOPE, joined.reuse());

        // A source the run's validated credential was not admitted for is not borrowed either.
        var wider = admit(a, "key-a", AccountSyncStateRepository.BANK, AccountSyncStateRepository.MATERIALS);
        assertEquals(ScopedAccountRefreshCoordinator.REUSE_WAITING_FOR_ACTIVE_TASK, wider.reuse());

        // A wider key cannot be adopted by the narrow run: it waits, and its key is not used.
        AccountSessions.Issued aWide = session(A, "key-a-wide", WIDE);
        var waiting = admit(aWide, "key-a-wide", AccountSyncStateRepository.CHARACTERS);
        assertEquals(ScopedAccountRefreshCoordinator.REUSE_WAITING_FOR_ACTIVE_TASK, waiting.reuse());

        gateway.release.countDown();
        awaitTerminal(first.taskId());
        assertEquals(List.of("A:BANK:key-a"), gateway.callsFor(A));
        assertTrue(coordinator.outstandingSources(AccountScope.of(A)).isEmpty());

        // After terminal work, the recheck admits the remaining source with its own validated key.
        var retry = admit(aWide, "key-a-wide", AccountSyncStateRepository.CHARACTERS);
        assertEquals(ScopedAccountRefreshCoordinator.REUSE_ADMITTED, retry.reuse());
        awaitTerminal(retry.taskId());
        assertEquals(List.of("A:BANK:key-a", "A:CHARACTERS:key-a-wide"), gateway.callsFor(A));
    }

    @Test
    void workerKeepsScopeAfterLogoutAndReleasesTheCredentialOnTerminal() throws Exception {
        AccountSessions.Issued a = session(A, "key-a", NARROW);
        gateway.blockAccount = A;
        var admitted = admit(a, "key-a", AccountSyncStateRepository.BANK);
        assertTrue(gateway.started.await(5, TimeUnit.SECONDS));

        assertTrue(sessions.revoke(a.sessionId()));
        assertFalse(sessions.resolve(a.sessionId(), a.context().accessContext()).authenticated());

        gateway.release.countDown();
        awaitTerminal(admitted.taskId());
        assertEquals(List.of("A:BANK:key-a"), gateway.callsFor(A));
        assertEquals(Set.of(AccountSyncStateRepository.BANK), coordinator.lastRefreshedSources(AccountScope.of(A)));
        assertFalse(coordinator.isActive(AccountScope.of(A)));
        // the fetcher the worker held can no longer produce a credentialed request
        assertThrows(IllegalStateException.class, () -> gateway.lastFetcher.get("https://x"));
    }

    @Test
    void failureMetadataIsSanitizedAndOtherSourcesStillCommit() throws Exception {
        java.util.logging.Logger taskLog = java.util.logging.Logger.getLogger(BackgroundTaskService.class.getName());
        StringBuilder logged = new StringBuilder();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                logged.append(record.getMessage()).append(' ').append(java.util.Arrays.toString(record.getParameters()));
                for (Throwable t = record.getThrown(); t != null; t = t.getCause()) {
                    logged.append(' ').append(t);
                    for (Throwable s : t.getSuppressed()) logged.append(' ').append(s);
                    java.io.StringWriter w = new java.io.StringWriter();
                    t.printStackTrace(new java.io.PrintWriter(w));
                    logged.append(w);
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        taskLog.addHandler(capture);
        try {
            AccountSessions.Issued a = session(A, "secret-key-value", WIDE);
            gateway.failSource = AccountSyncStateRepository.BANK;
            var admitted = admit(a, "secret-key-value", AccountSyncStateRepository.BANK,
                    AccountSyncStateRepository.CHARACTERS);
            awaitTerminal(admitted.taskId());

            assertEquals(List.of("A:BANK:secret-key-value", "A:CHARACTERS:secret-key-value"), gateway.callsFor(A));
            var failure = coordinator.lastFailure(AccountScope.of(A)).orElseThrow();
            var snapshot = service.find(admitted.taskId()).orElseThrow();
            assertEquals("ACCOUNT_SYNC", snapshot.operation());

            // The complete retained failure, including its chain and stack trace text.
            Throwable retained = snapshot.failure();
            assertNotNull(retained);
            assertNull(retained.getCause());
            assertEquals(0, retained.getSuppressed().length);
            java.io.StringWriter trace = new java.io.StringWriter();
            retained.printStackTrace(new java.io.PrintWriter(trace));
            String everything = failure + " " + snapshot + " " + trace + " " + logged;
            for (String secret : List.of("secret-key-value", "upstream-body-secret", "cause-secret-text",
                    "suppressed-secret-text")) {
                assertFalse(everything.contains(secret), "leaked " + secret);
            }
            assertTrue(logged.length() > 0, "the task boundary must still log the (sanitized) failure");
            assertFalse(coordinator.isActive(AccountScope.of(A)));
        } finally {
            taskLog.removeHandler(capture);
        }
    }

    @Test
    void characterSourceNeedsInventoriesBecauseBagsAndEquipmentArePublished() {
        keys.put("chars-only", new AccountProof(A, Set.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS), Set.of(), null));
        AccountSessions.Issued session = sessions.establish(keys.get("chars-only"));

        assertEquals(ScopedAccountRefreshCoordinator.Reason.INSUFFICIENT_GRANTS,
                assertThrows(ScopedAccountRefreshCoordinator.Rejected.class,
                        () -> admit(session, "chars-only", AccountSyncStateRepository.CHARACTERS)).reason());

        assertTrue(gateway.calls.isEmpty());
        assertEquals(0, fetcherRequests.get(), "no source fetch may have happened");
        assertFalse(coordinator.isActive(AccountScope.of(A)));
    }

    @Test
    void observationEnforcesEndpointRestrictionsNotJustGrantNames() throws Exception {
        AccountSessions.Issued a = session(A, "key-a", WIDE);
        var admitted = admit(a, "key-a", AccountSyncStateRepository.BANK);
        awaitTerminal(admitted.taskId());

        // Same GUID, same grants, but a replacement key limited to the materials endpoint.
        AccountContext materialsOnly = new AccountContext(A, WIDE, Set.of("/v2/account/materials"),
                a.context().accessContext(), a.context().expiresAt());
        assertTrue(coordinator.observe(materialsOnly, admitted.taskId()).isEmpty());
        assertTrue(coordinator.observe(a.context(), admitted.taskId()).isPresent());
        AccountContext bankToo = new AccountContext(A, WIDE, Set.of("/v2/account/bank"),
                a.context().accessContext(), a.context().expiresAt());
        assertTrue(coordinator.observe(bankToo, admitted.taskId()).isPresent());
    }

    @Test
    void retainedRecordIsObservableOnlyByTheOwnerWithRequiredGrants() throws Exception {
        AccountSessions.Issued a = session(A, "key-a", WIDE);
        AccountSessions.Issued b = session(B, "key-b", WIDE);
        var admitted = admit(a, "key-a", AccountSyncStateRepository.CHARACTERS);
        awaitTerminal(admitted.taskId());

        var record = coordinator.observe(a.context(), admitted.taskId()).orElseThrow();
        assertEquals(A, record.accountId());
        assertTrue(record.requiredGrants().containsAll(Set.of(AccountGrant.ACCOUNT, AccountGrant.CHARACTERS)));
        assertFalse(record.toString().contains("key-a"));
        assertTrue(coordinator.observe(b.context(), admitted.taskId()).isEmpty());
        AccountContext lacking = new AccountContext(A, NARROW, Set.of(), a.context().accessContext(),
                a.context().expiresAt());
        assertTrue(coordinator.observe(lacking, admitted.taskId()).isEmpty());
    }

    private void awaitTerminal(String taskId) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            var task = service.find(taskId);
            if (task.isPresent() && task.get().state().isTerminal()) return;
            Thread.sleep(10);
        }
        fail("task did not finish: " + taskId);
    }

    /** Offline gateway: records the credential each source fetch was handed. */
    private static final class Gateway extends AccountRefreshGateway {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        volatile String blockAccount;
        volatile String failSource;
        volatile AccountSync.AuthFetcher lastFetcher;

        List<String> callsFor(String account) {
            synchronized (calls) {
                return calls.stream().filter(c -> c.startsWith((account.equals(A) ? "A" : "B") + ":")).toList();
            }
        }

        private void record(AccountScope scope, String source, AccountSync.AuthFetcher fetcher) throws Exception {
            String label = scope.id().equals(A) ? "A" : "B";
            lastFetcher = fetcher;
            calls.add(label + ":" + source + ":" + fetcher.get("https://offline").asText());
            if (scope.id().equals(blockAccount)) {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            if (source.equals(failSource)) {
                IllegalStateException failure = new IllegalStateException(
                        "Unrecognized token 'upstream-body-secret' secret-key-value",
                        new RuntimeException("cause-secret-text"));
                failure.addSuppressed(new RuntimeException("suppressed-secret-text"));
                throw failure;
            }
        }

        @Override public void syncAccountBank(AccountScope s, AccountSync.AuthFetcher f) throws Exception {
            record(s, AccountSyncStateRepository.BANK, f);
        }
        @Override public void syncAccountMaterials(AccountScope s, AccountSync.AuthFetcher f) throws Exception {
            record(s, AccountSyncStateRepository.MATERIALS, f);
        }
        @Override public void syncAccountRecipes(AccountScope s, AccountSync.AuthFetcher f) throws Exception {
            record(s, AccountSyncStateRepository.RECIPES, f);
        }
        @Override public void syncAccountLuck(AccountScope s, AccountSync.AuthFetcher f) throws Exception {
            record(s, AccountSyncStateRepository.LUCK, f);
        }
        @Override public void syncCharacterCraftingAndRecipes(AccountScope s, AccountSync.AuthFetcher f)
                throws Exception {
            record(s, AccountSyncStateRepository.CHARACTERS, f);
        }
    }
}
