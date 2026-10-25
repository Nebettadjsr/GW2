package application;

import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountSessions;
import model.AccountScope;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedAccountRefreshRetentionTest {
    @Test
    void activeOwnersRemainObservableBeyondTheTerminalHistoryLimit() throws Exception {
        CountDownLatch started = new CountDownLatch(101);
        CountDownLatch release = new CountDownLatch(1);
        var sessions = new AccountSessions(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC), 120);
        Map<String, AccountProof> proofs = new ConcurrentHashMap<>();
        var owners = new ArrayList<AccountSessions.Issued>();
        var taskIds = new ArrayList<String>();
        var gateway = new AccountRefreshGateway() {
            @Override
            public void syncAccountBank(AccountScope scope, AccountSync.AuthFetcher fetcher) throws Exception {
                started.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("not released");
            }
        };
        try (var tasks = new BackgroundTaskService()) {
            var coordinator = new ScopedAccountRefreshCoordinator(new AccountRefreshService(gateway),
                    new BackgroundTaskServiceTasks(tasks), new AccountAccessService(proofs::get, sessions),
                    key -> url -> { throw new AssertionError("the controlled gateway must not fetch"); });
            try {
                for (int i = 1; i <= 101; i++) {
                    String key = "offline-key-" + i;
                    var proof = new AccountProof(new UUID(0, i).toString(),
                            Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES), Set.of(), null);
                    proofs.put(key, proof);
                    var owner = sessions.establish(proof);
                    owners.add(owner);
                    taskIds.add(coordinator.require(owner.context(), owner.sessionId(), key,
                            Set.of(AccountSyncStateRepository.BANK)).taskId());
                }
                assertTrue(started.await(20, TimeUnit.SECONDS));
                for (int i = 0; i < owners.size(); i++) {
                    assertTrue(coordinator.observe(owners.get(i).context(), taskIds.get(i)).isPresent(),
                            "unfinished task ownership must not be evicted: " + i);
                    assertFalse(tasks.find(taskIds.get(i)).orElseThrow().state().isTerminal());
                }
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (taskIds.stream().anyMatch(id -> !tasks.find(id).orElseThrow().state().isTerminal())
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(taskIds.stream().allMatch(id -> tasks.find(id).orElseThrow().state().isTerminal()));
                assertTrue(coordinator.observe(owners.getFirst().context(), taskIds.getFirst()).isEmpty(),
                        "the cap applies once ownership becomes terminal history");
                assertTrue(coordinator.lastRefreshedSources(AccountScope.of(owners.getFirst().context().accountId()))
                        .isEmpty(), "evicted terminal ownership must not leave unbounded per-account history");
                assertTrue(coordinator.observe(owners.getLast().context(), taskIds.getLast()).isPresent());
            } finally {
                release.countDown();
            }
        }
    }
}
