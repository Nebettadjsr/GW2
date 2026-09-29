package web;

import application.AccountRefreshService;
import application.BankContentsService;
import application.CharacterSelectionService;
import application.CraftingDiscoveryService;
import application.CraftingProfitService;
import application.GlobalDataRefreshService;
import application.MaterialStorageService;
import application.TradingPostPriceRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import web.task.BackgroundTaskService;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Spring Boot entry point is genuinely runnable on this Java/Maven setup
 * (STORY-API-001 acceptance criterion 1): the context starts, an embedded server binds a port, and
 * the crafting calculation endpoints are wired to per-request application-service factories.
 *
 * <p>Deliberately does not call the endpoints - that would need the real database. Transport
 * behavior is covered by {@link CraftingProfitApiControllerTest} and
 * {@link CraftingDiscoveryApiControllerTest}, real-database timing by {@code *RealDbPerfIT}. The
 * service factories are never invoked here, so no connection is opened. The narrowly scoped
 * account-Luck schema initialization is disabled for this wiring-only test; normal startup runs it.
 *
 * <p>STORY-API-003 added the account synchronization trigger, the shared task-status route and the task
 * facility they use, STORY-API-004 the global one and STORY-API-005 the price-refresh one; this test
 * covers only that they are wired into the same context, since starting no task means starting no GW2
 * API call either. STORY-API-008 added the two resolution-detail operations to the existing crafting
 * controllers, so what this test adds for them is that their decided paths are really mapped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gw2.account-luck.schema-init=false")
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

    @Autowired
    private GlobalSyncApiController globalSyncController;

    @Autowired
    private GlobalDataRefreshService globalDataRefreshService;

    @Autowired
    private PriceRefreshApiController priceRefreshController;

    @Autowired
    private TradingPostPriceRefreshService tradingPostPriceRefreshService;

    @Autowired
    private CraftingSelectorOptionsApiController selectorOptionsController;

    @Autowired
    private CharacterSelectionService characterSelectionService;

    @Autowired
    private BankContentsApiController bankContentsController;

    @Autowired
    private BankContentsService bankContentsService;

    @Autowired
    private MaterialStorageApiController materialStorageController;

    @Autowired
    private MaterialStorageService materialStorageService;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void applicationStartsAndExposesTheCraftingControllersOverAnEmbeddedServer() {
        assertTrue(port > 0, "the embedded server must have bound a port");
        assertNotNull(controller);
        assertNotNull(discoveryController);
    }

    @Test
    void theSelectorOptionsRouteIsWiredToTheExistingCharacterSelectionUseCase() {
        assertNotNull(selectorOptionsController);
        assertNotNull(characterSelectionService,
                "the route delegates to the existing use case, so it must be a bean");
    }

    @Test
    void theAccountReadRoutesAreWiredToTheExistingBankAndMaterialUseCases() {
        assertNotNull(bankContentsController);
        assertNotNull(materialStorageController);
        assertNotNull(bankContentsService,
                "the bank route delegates to the existing use case, so it must be a bean");
        assertNotNull(materialStorageService,
                "the materials route delegates to the existing use case, so it must be a bean");
    }

    @Test
    void theSynchronizationTriggersAndTaskStatusRouteAreWiredToTheSharedTaskFacility() {
        assertNotNull(accountSyncController);
        assertNotNull(globalSyncController);
        assertNotNull(priceRefreshController);
        assertNotNull(syncTaskController);
        assertNotNull(backgroundTaskService);
        assertNotNull(accountRefreshService,
                "the trigger delegates to the existing use case, so it must be a bean");
        assertNotNull(globalDataRefreshService,
                "the global trigger delegates to the existing use case, so it must be a bean");
        assertNotNull(tradingPostPriceRefreshService,
                "the price-refresh trigger delegates to the existing use case, so it must be a bean");
    }

    /**
     * STORY-API-008: the two resolution-detail operations must be reachable at exactly the paths
     * TARGET_ARCHITECTURE.md §13.1 fixes, in the real context rather than only in a standalone
     * MockMvc setup. Still no call is made, so no connection is opened.
     */
    @Test
    void theResolutionDetailRoutesAreMappedAtTheirDecidedPaths() {
        Set<String> postPaths = handlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getMethodsCondition().getMethods().contains(RequestMethod.POST))
                .flatMap(info -> info.getPathPatternsCondition() == null
                        ? Stream.<String>empty()
                        : info.getPathPatternsCondition().getPatternValues().stream())
                .collect(Collectors.toSet());

        assertTrue(postPaths.contains("/api/crafting/profit/resolution"),
                "mapped POST paths: " + postPaths);
        assertTrue(postPaths.contains("/api/crafting/discovery/resolution"),
                "mapped POST paths: " + postPaths);
    }

    @Test
    void theRemovedEctoSalvageRouteIsNotMapped() {
        Set<String> getPaths = handlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getMethodsCondition().getMethods().contains(RequestMethod.GET))
                .flatMap(info -> info.getPathPatternsCondition() == null
                        ? Stream.<String>empty()
                        : info.getPathPatternsCondition().getPatternValues().stream())
                .collect(Collectors.toSet());

        assertFalse(getPaths.contains("/api/ecto/salvage"), "mapped GET paths: " + getPaths);
    }

    @Test
    void theConfiguredFactoryHandsOutAFreshApplicationServicePerCall() {
        assertNotSame(profitServiceFactory.get(), profitServiceFactory.get(),
                "a shared service would let requests observe each other's reload state");
        assertNotSame(discoveryServiceFactory.get(), discoveryServiceFactory.get(),
                "a shared service would let requests observe each other's reload state");
    }
}
