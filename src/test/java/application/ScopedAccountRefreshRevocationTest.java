package application;

import api.Gw2ApiClient;
import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountSessions;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** STORY-SYNC-006 / TARGET_ARCHITECTURE §8.1: worker-side definitive key rejection revokes the originating session. */
class ScopedAccountRefreshRevocationTest {

    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final Set<AccountGrant> WIDE = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES, AccountGrant.UNLOCKS,
            AccountGrant.CHARACTERS);

    private final BackgroundTaskService service = new BackgroundTaskService();
    private final AccountSessions sessions = new AccountSessions(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), 8);
    private final Map<String, AccountProof> keys = new ConcurrentHashMap<>();
    /** source -> failure to throw from the fetch of that source. */
    private final Map<String, RuntimeException> fetchFailures = new ConcurrentHashMap<>();
    private final Gateway gateway = new Gateway();
    private final ScopedAccountRefreshCoordinator coordinator = new ScopedAccountRefreshCoordinator(
            new AccountRefreshService(gateway), new BackgroundTaskServiceTasks(service),
            new AccountAccessService(key -> keys.get(key), sessions),
            key -> url -> {
                RuntimeException failure = fetchFailures.get(url);
                if (failure != null) throw failure;
                return TextNode.valueOf(key);
            });

    @AfterEach
    void close() {
        service.close();
    }

    private AccountSessions.Issued session(String key) {
        AccountProof proof = new AccountProof(A, WIDE, Set.of(), null);
        keys.put(key, proof);
        return sessions.establish(proof);
    }

    private boolean live(AccountSessions.Issued s) {
        return sessions.resolve(s.sessionId(), s.context().accessContext()).authenticated();
    }

    private void run(AccountSessions.Issued s, String key, String... sources) throws Exception {
        var admitted = coordinator.require(s.context(), s.sessionId(), key, Set.of(sources));
        for (int i = 0; i < 500; i++) {
            var task = service.find(admitted.taskId());
            if (task.isPresent() && task.get().state().isTerminal()) return;
            Thread.sleep(10);
        }
        fail("task did not finish");
    }

    @Test
    void definitiveRejectionMidWorkRevokesOnlyTheOriginatingSessionEvenWhenAnotherSourceFailedFirst()
            throws Exception {
        AccountSessions.Issued origin = session("key-1");
        AccountSessions.Issued replacement = session("key-2");
        fetchFailures.put(AccountSyncStateRepository.BANK, new IllegalStateException("bank outage"));
        fetchFailures.put(AccountSyncStateRepository.MATERIALS,
                new Gw2ApiClient.AuthRejectedException("https://offline/v2/account/materials", 401));

        run(origin, "key-1", AccountSyncStateRepository.BANK, AccountSyncStateRepository.MATERIALS,
                AccountSyncStateRepository.RECIPES);

        assertFalse(live(origin), "the originating session must be revoked");
        assertTrue(live(replacement), "a same-GUID replacement session stays live");
        assertEquals(Set.of(AccountSyncStateRepository.BANK, AccountSyncStateRepository.MATERIALS,
                AccountSyncStateRepository.RECIPES), gateway.attempted, "other sources were still attempted");
        assertTrue(coordinator.lastFailure(AccountScope.of(A)).orElseThrow().toString().indexOf("key-1") < 0);
        assertFalse(coordinator.isActive(AccountScope.of(A)));
    }

    @Test
    void forbiddenAlsoRevokesAndRetainedRecordsHoldNoSessionOrKey() throws Exception {
        AccountSessions.Issued origin = session("key-1");
        fetchFailures.put(AccountSyncStateRepository.BANK,
                new Gw2ApiClient.AuthRejectedException("https://offline/v2/account/bank", 403));

        var admitted = coordinator.require(origin.context(), origin.sessionId(), "key-1",
                Set.of(AccountSyncStateRepository.BANK));
        for (int i = 0; i < 500 && !service.find(admitted.taskId()).get().state().isTerminal(); i++) Thread.sleep(10);

        assertFalse(live(origin));
        var record = coordinator.observe(origin.context(), admitted.taskId()).orElseThrow();
        String retained = record + " " + service.find(admitted.taskId()).orElseThrow();
        assertFalse(retained.contains("key-1"));
        assertFalse(retained.contains(origin.sessionId()));
    }

    @Test
    void transientAndMalformedFailuresKeepTheSessionLive() throws Exception {
        for (RuntimeException failure : new RuntimeException[] {
                new RuntimeException("GET https://offline failed: HTTP 429"),
                new RuntimeException("GET https://offline failed: HTTP 503"),
                new IllegalStateException(new java.io.IOException("malformed JSON")),
                new IllegalStateException("transport down")}) {
            AccountSessions.Issued origin = session("key-t");
            fetchFailures.put(AccountSyncStateRepository.BANK, failure);
            run(origin, "key-t", AccountSyncStateRepository.BANK);
            assertTrue(live(origin), "must stay live after " + failure.getMessage());
        }
    }

    private final class Gateway extends AccountRefreshGateway {
        final Set<String> attempted = ConcurrentHashMap.newKeySet();

        @Override public void syncAccountBank(AccountScope s, AccountSync.AuthFetcher f) throws Exception {
            fetchThrowing(AccountSyncStateRepository.BANK, f);
        }
        @Override public void syncAccountMaterials(AccountScope s, AccountSync.AuthFetcher f) throws Exception {
            fetchThrowing(AccountSyncStateRepository.MATERIALS, f);
        }
        @Override public void syncAccountRecipes(AccountScope s, AccountSync.AuthFetcher f) throws Exception {
            fetchThrowing(AccountSyncStateRepository.RECIPES, f);
        }

        private void fetchThrowing(String source, AccountSync.AuthFetcher f) throws Exception {
            attempted.add(source);
            f.get(source); // the worker's fetcher is where the upstream answer is seen
        }
    }
}
