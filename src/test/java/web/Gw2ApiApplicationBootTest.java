package web;

import application.AccountRefreshService;
import application.CraftingDiscoveryService;
import application.CraftingProfitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import web.task.BackgroundTaskService;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Spring Boot entry point is genuinely runnable on this Java/Maven setup
 * (STORY-API-001 acceptance criterion 1): the context starts, an embedded server binds a port, and
 * the crafting calculation endpoints are wired to per-request application-service factories.
 *
 * <p>Deliberately does not call the endpoints - that would need the real database. Transport
 * behavior is covered by {@link CraftingProfitApiControllerTest} and
 * {@link CraftingDiscoveryApiControllerTest}, real-database timing by {@code *RealDbPerfIT}. The
 * service factories are never invoked here, so no connection is opened.
 *
 * <p>STORY-API-003 added the synchronization trigger, the shared task-status route and the task
 * facility they use; this test covers only that they are wired into the same context, since starting no
 * task means starting no GW2 API call either.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Gw2ApiApplicationBootTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CraftingProfitApiController controller;

    @Autowired
    private CraftingDiscoveryApiController discoveryController;

    @Autowired
    private Supplier<CraftingProfitService> profitServiceFactory;

    @Autowired
    private Supplier<CraftingDiscoveryService> discoveryServiceFactory;

    @Autowired
    private AccountSyncApiController accountSyncController;

    @Autowired
    private SyncTaskApiController syncTaskController;

    @Autowired
    private BackgroundTaskService backgroundTaskService;

    @Autowired
    private AccountRefreshService accountRefreshService;

    @Test
    void applicationStartsAndExposesTheCraftingControllersOverAnEmbeddedServer() {
        assertTrue(port > 0, "the embedded server must have bound a port");
        assertNotNull(controller);
        assertNotNull(discoveryController);
    }

    @Test
    void theSynchronizationTriggerAndTaskStatusRouteAreWiredToTheSharedTaskFacility() {
        assertNotNull(accountSyncController);
        assertNotNull(syncTaskController);
        assertNotNull(backgroundTaskService);
        assertNotNull(accountRefreshService,
                "the trigger delegates to the existing use case, so it must be a bean");
    }

    @Test
    void theConfiguredFactoryHandsOutAFreshApplicationServicePerCall() {
        assertNotSame(profitServiceFactory.get(), profitServiceFactory.get(),
                "a shared service would let requests observe each other's reload state");
        assertNotSame(discoveryServiceFactory.get(), discoveryServiceFactory.get(),
                "a shared service would let requests observe each other's reload state");
    }
}
