package web;

import application.AccountRefreshCoordinator;
import application.AccountRefreshService;
import application.BackgroundTasks;
import application.BankContentsService;
import application.CachedCraftingProfitService;
import application.CalculationInputVersions;
import application.CharacterSelectionService;
import application.CraftingDiscoveryService;
import application.CraftingProfitService;
import application.ProfitInputSession;
import application.TradingPostRefreshCoordinator;
import application.AccountLuckService;
import application.ScopedAccountRefreshCoordinator;
import application.GlobalDataRefreshService;
import application.MaterialStorageService;
import application.ItemReadService;
import application.TradingPostPriceRefreshService;
import application.TradingPostPriceCacheStatusService;
import application.access.AccountAccessService;
import application.access.AccountProofPort;
import application.access.AccountSessions;
import application.icons.IconDelivery;
import infra.access.FixtureAccountProofAdapter;
import infra.access.Gw2AccountProofAdapter;
import infra.icons.FilesystemIconStore;
import infra.icons.HttpIconImageFetcher;
import infra.icons.IconAcquisition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import repo.AppConfig;
import repo.AccountLuckSchema;
import repo.CraftingGraphCache;
import repo.ItemIconMetadataRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import web.access.AccountAccessHttp;
import web.task.BackgroundTaskService;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Spring Boot entry point for the backend HTTP API (STORY-API-001, UD-006).
 *
 * <p>Additive: the JavaFX application keeps its own {@code Gw2App} entry point and keeps calling
 * the application services in process. Starting this class starts only the HTTP boundary.
 *
 * <p>Run with {@code ./mvnw spring-boot:run}. The port comes from configuration
 * ({@code GW2_API_PORT}, see {@code application.properties}), never from a hard-coded value.
 */
@SpringBootApplication
@EnableScheduling
public class Gw2ApiApplication {

    public static void main(String[] args) {
        // The startup schema gate runs before the Spring context exists, so no repository, task or
        // scheduler can reach the database before the versioned chain is validated or applied, and
        // the process stops here when it cannot be (STORY-INFRA-004, TARGET_ARCHITECTURE.md §7.3).
        infra.db.SchemaInitializer.initializeRuntimeDatabase();

        SpringApplication.run(Gw2ApiApplication.class, args);
    }

    /** Adapts the shared task facility to the application layer's refresh-admission port. */
    @Bean
    public BackgroundTasks backgroundTasks(BackgroundTaskService tasks) {
        return new BackgroundTaskServiceTasks(tasks);
    }

    /**
     * Single-writer coordination for account sources, shared across every entry point that can
     * demand them (STORY-APP-014): one instance is what keeps the accumulated demand, the one
     * account writer and the "never claim an unrefreshed source" rule in force at the same time.
     */
    @Bean
    public AccountRefreshCoordinator accountRefreshCoordinator(AccountRefreshService refresh,
                                                               BackgroundTasks tasks) {
        return new AccountRefreshCoordinator(refresh, tasks);
    }

    /**
     * The account-keyed refresh coordinator the HTTP routes admit through (STORY-API-011). Its only
     * credential is the transient key admitted with a run: the fetcher factory binds that key to
     * every upstream GET and never consults the deployment key.
     */
    @Bean
    public ScopedAccountRefreshCoordinator scopedAccountRefreshCoordinator(
            AccountRefreshService refresh, BackgroundTasks tasks, AccountAccessService access) {
        return new ScopedAccountRefreshCoordinator(refresh, tasks, access,
                key -> url -> api.Gw2ApiClient.getAuth(url, key));
    }

    /** Production reader behind the System Status own-account projection; connects only when called. */
    @Bean
    public SystemStatusApiController.AccountStateReader accountStateReader() {
        return (scope, sources) -> {
            try (java.sql.Connection con = repo.Db.open()) {
                repo.ManagedSchema.requireManagedAccountSchema(con, "account status");
                return new repo.AccountSourceStateRepository().readAll(con, scope, sources);
            }
        };
    }

    /** Shared Trading Post demand coordination, deliberately separate from the account one. */
    @Bean
    public TradingPostRefreshCoordinator tradingPostRefreshCoordinator(
            TradingPostPriceRefreshService refresh, BackgroundTasks tasks) {
        return new TradingPostRefreshCoordinator(refresh, tasks);
    }

    /**
     * Crafting Profit's cached-result use case (TARGET_ARCHITECTURE.md §9.1), shared rather than
     * per-request: it holds no per-call state, and the coordinators it uses must be the shared ones
     * for their admission rules to mean anything. The per-request state stays where it was — each
     * call builds its own {@link CraftingProfitService} over its own captured session, which is what
     * keeps concurrent calculations from observing each other.
     *
     * <p>Constructing it opens nothing: a session is opened inside a call and closed before it
     * returns, so this bean costs no database connection at startup.
     */
    @Bean
    public CachedCraftingProfitService cachedCraftingProfitService(
            CraftingGraphCache graphCache,
            AccountRefreshCoordinator accountRefreshCoordinator,
            TradingPostRefreshCoordinator tradingPostRefreshCoordinator,
            ScopedAccountRefreshCoordinator scopedAccountRefreshCoordinator,
            BackgroundTasks tasks,
            @Value("${gw2.account-data.max-age-ms:900000}") long accountMaxAgeMs) {
        return new CachedCraftingProfitService(
                new ProfitInputSession.Factory() {
                    @Override
                    public ProfitInputSession open() throws java.sql.SQLException {
                        return ProfitInputSession.open();
                    }

                    @Override
                    public ProfitInputSession open(model.AccountScope scope) throws java.sql.SQLException {
                        return ProfitInputSession.open(scope);
                    }
                },
                session -> new CraftingProfitService(graphCache, session),
                new CalculationInputVersions.DatabaseAccountSourceVersionReader(),
                new CalculationInputVersions.DatabasePriceCoverageReader(),
                accountRefreshCoordinator,
                tradingPostRefreshCoordinator,
                tasks,
                Clock.systemUTC(),
                Duration.ofMillis(accountMaxAgeMs)).withScopedRefresh(scopedAccountRefreshCoordinator)
                .withCharacterRoster(scope -> new repo.CharacterRepository().loadAllCharacterNames(scope));
    }

    /**
     * Supplies a fresh {@link CraftingDiscoveryService} per request, for the same reason
     * (STORY-API-002). Discovery additionally keeps its previous reload's lookup state when a
     * reload finds nothing to discover, so a shared instance could answer an empty request with an
     * earlier one's data.
     */
    @Bean
    public Supplier<CraftingDiscoveryService> craftingDiscoveryServiceFactory(
            CraftingGraphCache graphCache,
            @Value("${gw2.account-data.max-age-ms:900000}") long accountMaxAgeMs) {
        return () -> new CraftingDiscoveryService(graphCache)
                .withFreshness(Clock.systemUTC(), Duration.ofMillis(accountMaxAgeMs));
    }

    /** Shared immutable catalog snapshot for calculations, ingredient search and refresh invalidation. */
    @Bean
    public CraftingGraphCache craftingGraphCache() {
        return new CraftingGraphCache(new RecipeRepository(), new ItemRepository());
    }

    /**
     * The one background-task facility the synchronization triggers share (STORY-API-003). Shared
     * deliberately: its admission rule is what stops two account syncs from overlapping, which a
     * per-request instance could not do. Spring closes it on shutdown, since it is
     * {@link AutoCloseable}.
     */
    @Bean
    public BackgroundTaskService backgroundTaskService() {
        return new BackgroundTaskService();
    }

    /**
     * The account-refresh use case, as one shared instance rather than a per-request factory.
     *
     * <p>The reason the calculation services need a factory does not apply here: {@link
     * AccountRefreshService} keeps no per-call state, holding only its {@code sync.AccountRefreshGateway}
     * collaborator, so there is no reload result for two callers to observe. Overlapping account syncs
     * are excluded by the task facility's admission rule instead.
     */
    @Bean
    public AccountRefreshService accountRefreshService() {
        return new AccountRefreshService();
    }

    /**
     * The global-data-refresh use case (STORY-API-004), shared for the same reason as
     * {@link #accountRefreshService()}: it keeps no per-call state, holding only its
     * {@code sync.GlobalDataRefreshGateway} and {@code application.CraftingGraphRebuildService}
     * collaborators. Overlapping global syncs are excluded by the task facility's admission rule.
     *
     * <p>Constructing it opens nothing — the gateway wraps static sync utilities and the rebuild
     * service holds a {@code repo.CraftingGraphCache} that connects only when it runs — so this bean
     * costs no database connection at startup.
     */
    @Bean
    public GlobalDataRefreshService globalDataRefreshService(CraftingGraphCache graphCache) {
        return new GlobalDataRefreshService(graphCache);
    }

    /**
     * The Trading Post price-refresh use case (STORY-API-005), shared for the same reason as the two
     * above: it keeps no per-call state, holding only its {@code sync.TradingPostPriceRefreshGateway}
     * collaborator, so its two variants have no state for concurrent callers to observe. Overlapping
     * refreshes of the <em>same</em> variant are excluded by the task facility's admission rule.
     *
     * <p>Constructing it opens nothing — the gateway wraps static {@code sync.TpSync} utilities that
     * connect only when a refresh runs — so this bean costs no database connection at startup.
     */
    @Bean
    public TradingPostPriceRefreshService tradingPostPriceRefreshService() {
        return new TradingPostPriceRefreshService();
    }

    @Bean
    public TradingPostPriceCacheStatusService tradingPostPriceCacheStatusService() {
        return new TradingPostPriceCacheStatusService();
    }

    /**
     * The crafting selector read (STORY-API-006), shared rather than per-request: it keeps no
     * per-call state, holding only its {@code repo.CharacterRepository} collaborator, so there is no
     * reload result for two callers to observe. The repository opens its connection inside the call,
     * so this bean costs no database connection at startup.
     */
    @Bean
    public CharacterSelectionService characterSelectionService() {
        return new CharacterSelectionService();
    }

    /**
     * The account-bank read (STORY-API-007), shared for the same reason as
     * {@link #characterSelectionService()}: it keeps no per-call state, holding only its
     * {@code repo.BankRepository} collaborator, which opens its connection inside the call — so this
     * bean costs no database connection at startup and two callers share no read result.
     */
    @Bean
    public BankContentsService bankContentsService() {
        return new BankContentsService();
    }

    /**
     * The material-storage read (STORY-API-007), shared for the same reason: it keeps no per-call
     * state, holding only its {@code repo.MaterialStorageRepository} collaborator, and its grouping
     * is computed from the rows of the call that asked for it.
     */
    @Bean
    public MaterialStorageService materialStorageService() {
        return new MaterialStorageService();
    }

    /** Scoped Luck read; the progression is global, the stored consumed Luck is the account's. */
    @Bean
    public AccountLuckService accountLuckService(
            @Value("${gw2.account-luck.schema-init:true}") boolean initializeSchema) throws SQLException {
        if (initializeSchema) AccountLuckSchema.ensure();
        return new AccountLuckService();
    }

    @Bean
    public ItemReadService itemReadService() {
        return new ItemReadService();
    }

    /**
     * Upstream validation of a caller-supplied GW2 key (STORY-API-010, TARGET_ARCHITECTURE.md §8.1).
     *
     * <p>The live adapter is the default. {@code gw2.account-access.test-fixtures} replaces it with
     * the offline fixture adapter so the HTTP access contract can be verified without sending a
     * credential to ArenaNet; it is never set in a deployment, and its fixtures live in test
     * resources.
     */
    @Bean
    public AccountProofPort accountProofPort(
            @Value("${gw2.account-access.test-fixtures:false}") boolean useFixtures) {
        return useFixtures
                ? new FixtureAccountProofAdapter(Clock.systemUTC())
                : new Gw2AccountProofAdapter();
    }

    /**
     * The one process-local session store. Shared deliberately: a per-request instance would issue
     * sessions nothing could resolve, and the bound on retained sessions only means something while
     * one instance holds them all. Restart loses them, which the contract requires - proof again.
     */
    @Bean
    public AccountSessions accountSessions(
            @Value("${gw2.account-access.session-capacity:500}") int capacity) {
        return new AccountSessions(Clock.systemUTC(), capacity);
    }

    @Bean
    public AccountAccessService accountAccessService(AccountProofPort proof, AccountSessions sessions) {
        return new AccountAccessService(proof, sessions);
    }

    /** Source-state handling for the keyless account reads (STORY-API-011). */
    @Bean
    public application.ScopedSourceGate scopedSourceGate(
            @Value("${gw2.account-data.max-age-ms:900000}") long accountMaxAgeMs) {
        return application.ScopedSourceGate.database(Clock.systemUTC(), Duration.ofMillis(accountMaxAgeMs));
    }

    /** The transport rules the session routes and the account guard must apply identically. */
    @Bean
    public AccountAccessHttp accountAccessHttp(
            @Value("${gw2.account-access.allowed-origins:}") String allowedOrigins) {
        return new AccountAccessHttp(allowedOrigins);
    }

    /**
     * The icon-delivery boundary (STORY-API-009, TARGET_ARCHITECTURE.md §12.1) over the persistent
     * filesystem cache at {@code ICON_CACHE_DIR} and the upstream image adapter.
     *
     * <p>Shared on purpose, unlike the calculation services: the in-process miss coordination is the
     * whole point of {@link IconAcquisition} - its download bound, its waiting bound, its per-key
     * coalescing and its short failure suppression only hold while one instance serves every request.
     * The store instance is shared with it so both sides of a miss use the same root and the same
     * publication protocol.
     *
     * <p>Constructing it touches no disk and opens no connection: the store creates its directory when
     * something is first published, and the metadata repository connects inside the call.
     */
    @Bean
    public IconDelivery iconDelivery() {
        FilesystemIconStore store = new FilesystemIconStore(Path.of(AppConfig.ICON_CACHE_DIR));
        return new IconDelivery(
                new ItemIconMetadataRepository(),
                store,
                new IconAcquisition(store, new HttpIconImageFetcher()));
    }
}
