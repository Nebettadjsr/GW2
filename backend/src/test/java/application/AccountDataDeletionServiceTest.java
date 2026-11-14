package application;

import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountLifecycleGate;
import application.access.AccountProof;
import application.access.AccountSessions;
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

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** STORY-DELETE-001 fence, ordering and session behaviour; offline, deterministic latches. */
class AccountDataDeletionServiceTest {

    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final String B = "22222222-2222-2222-2222-222222222222";
    private static final Set<AccountGrant> GRANTS = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final BackgroundTaskService service = new BackgroundTaskService();
    private final AccountSessions sessions = new AccountSessions(clock, 16);
    private final AccountAccessService access = new AccountAccessService(
            key -> new AccountProof(key.startsWith("a") ? A : B, GRANTS, Set.of(), null), sessions);
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ScopedAccountRefreshCoordinator coordinator = new ScopedAccountRefreshCoordinator(
            new AccountRefreshService(new AccountRefreshGateway() {
                @Override public void syncAccountBank(AccountScope s, AccountSync.AuthFetcher f) throws Exception {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                }
            }), new BackgroundTaskServiceTasks(service), access, key -> url -> (JsonNode) TextNode.valueOf(key));
    private final AtomicInteger deletes = new AtomicInteger();

    @AfterEach
    void close() {
        release.countDown();
        service.close();
    }

    private AccountDataDeletionService service(AccountDataDeletionService.Deleter deleter) {
        return new AccountDataDeletionService(access, coordinator, deleter);
    }

    private AccountSessions.Issued session(String key) throws Exception {
        return access.establish(key);
    }

    private static AccountScope scope(String id) {
        return AccountScope.of(id);
    }

    @Test
    void unfinishedRefreshReturnsConflictWithoutDeletingThenSucceedsAfterCompletion() throws Exception {
        AccountSessions.Issued a = session("a1");
        var admission = coordinator.require(a.context(), a.sessionId(), "a1", Set.of(AccountSyncStateRepository.BANK));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var svc = service(s -> deletes.incrementAndGet());

        assertEquals(AccountDataDeletionService.Result.WORK_IN_PROGRESS, svc.delete(scope(A), 0));
        assertEquals(0, deletes.get());
        assertTrue(sessions.resolve(a.sessionId(), a.context().accessContext()).authenticated());

        release.countDown();
        for (int i = 0; i < 200 && coordinator.hasUnfinishedWork(scope(A)); i++) Thread.sleep(20);
        assertEquals(AccountDataDeletionService.Result.DELETED, svc.delete(scope(A), 0));
        assertEquals(1, deletes.get());
        assertNotNull(admission.taskId());
    }

    @Test
    void deletionWaitsForAdmittedOperationThenRunsAndBlocksNewOnesWhileHeld() throws Exception {
        var hold = new CountDownLatch(1);
        var inDelete = new CountDownLatch(1);
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var svc = service(s -> {
            order.add("delete");
            inDelete.countDown();
            try { hold.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new SQLException(e); }
        });
        AccountLifecycleGate gate = new AccountLifecycleGate(Duration.ofMillis(300));
        // Use the access service's own gate for the real wiring; the unit gate above checks timeouts.
        var admitted = access.lifecycle().enter(UUID.fromString(A));
        CompletableFuture<AccountDataDeletionService.Result> deletion =
                CompletableFuture.supplyAsync(() -> svc.delete(scope(A), 0));
        Thread.sleep(150);
        assertFalse(deletion.isDone());
        assertTrue(order.isEmpty(), "deletion must wait for the admitted operation");
        order.add("activity-write");
        admitted.close();
        assertTrue(inDelete.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("activity-write", "delete"), order);

        // Deletion holds the writer position: A refresh and session installation are not admitted,
        // B and global work are.
        assertThrows(AccountLifecycleGate.Unavailable.class, () -> access.lifecycle().requireOpen(UUID.fromString(A)));
        access.lifecycle().requireOpen(UUID.fromString(B));
        access.lifecycle().enter(UUID.fromString(B)).close();
        CompletableFuture<AccountSessions.Issued> installing = CompletableFuture.supplyAsync(() -> {
            try { return session("a-late"); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(150);
        assertFalse(installing.isDone(), "session installation waits for the gate");
        assertTrue(session("b1").context().accountId().equals(B));

        hold.countDown();
        assertEquals(AccountDataDeletionService.Result.DELETED, deletion.get(5, TimeUnit.SECONDS));
        // The session that was waiting is installed after the gate reopens, as a normal new session.
        var late = installing.get(5, TimeUnit.SECONDS);
        assertTrue(sessions.resolve(late.sessionId(), late.context().accessContext()).authenticated());
        assertNotNull(gate);
    }

    @Test
    void rollbackReopensGateKeepsSessionsAndCoordinatorState() throws Exception {
        AccountSessions.Issued a = session("a1");
        coordinator.require(a.context(), a.sessionId(), "a1", Set.of(AccountSyncStateRepository.BANK));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        release.countDown();
        for (int i = 0; i < 200 && coordinator.hasUnfinishedWork(scope(A)); i++) Thread.sleep(20);
        var svc = service(s -> { throw new SQLException("injected"); });

        assertEquals(AccountDataDeletionService.Result.UNAVAILABLE, svc.delete(scope(A), 0));
        assertTrue(sessions.resolve(a.sessionId(), a.context().accessContext()).authenticated());
        assertFalse(coordinator.lastRefreshedSources(scope(A)).isEmpty(), "coordinator state untouched on rollback");
        access.lifecycle().requireOpen(UUID.fromString(A));
    }

    @Test
    void commitDeletionInvalidatesEveryAccountSessionAndLeavesOthersAndLaterConnect() throws Exception {
        AccountSessions.Issued a1 = session("a1");
        AccountSessions.Issued a2 = session("a2");
        AccountSessions.Issued b = session("b1");
        var svc = service(s -> deletes.incrementAndGet());

        assertEquals(AccountDataDeletionService.Result.DELETED, svc.delete(scope(A), 0));
        assertEquals(AccountDataDeletionService.Result.DELETED, svc.delete(scope(A), 0), "idempotent");
        for (var s : List.of(a1, a2)) {
            assertEquals(AccountSessions.Outcome.DELETION_INVALIDATED,
                    sessions.resolve(s.sessionId(), s.context().accessContext()).outcome());
            // Not "context mismatch" either: a wrong context still reports the deletion.
            assertEquals(AccountSessions.Outcome.DELETION_INVALIDATED,
                    sessions.resolve(s.sessionId(), "wrong").outcome());
        }
        assertTrue(sessions.resolve(b.sessionId(), b.context().accessContext()).authenticated());

        AccountSessions.Issued reconnect = session("a3");
        assertTrue(sessions.resolve(reconnect.sessionId(), reconnect.context().accessContext()).authenticated());

        // Retention ends with the original lease: the invalidated verifier is gone afterwards.
        now.set(now.get().plus(AccountSessions.LEASE).plusSeconds(1));
        assertEquals(AccountSessions.Outcome.EXPIRED,
                sessions.resolve(a1.sessionId(), a1.context().accessContext()).outcome());
        assertEquals(AccountSessions.Outcome.NO_SESSION,
                sessions.resolve(a1.sessionId(), a1.context().accessContext()).outcome());
        assertEquals(0, sessions.size());
    }
}
