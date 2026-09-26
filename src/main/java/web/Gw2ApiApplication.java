package web;

import application.AccountRefreshService;
import application.BankContentsService;
import application.CharacterSelectionService;
import application.CraftingDiscoveryService;
import application.CraftingProfitService;
import application.GlobalDataRefreshService;
import application.MaterialStorageService;
import application.TradingPostPriceRefreshService;
import application.icons.IconDelivery;
import infra.icons.FilesystemIconStore;
import infra.icons.HttpIconImageFetcher;
import infra.icons.IconAcquisition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import repo.AppConfig;
import repo.ItemIconMetadataRepository;
import web.task.BackgroundTaskService;

import java.nio.file.Path;
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
public class Gw2ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(Gw2ApiApplication.class, args);
    }

    /**
     * Supplies a fresh {@link CraftingProfitService} per request.
     *
     * <p>A plain {@link Supplier} rather than a Spring-scoped bean, so the application layer stays
     * free of framework annotations and the controller's seam is trivially substitutable in tests.
     * Each service instance owns the reload state for exactly one request, which is what keeps
     * concurrent calculations from observing each other.
     */
    @Bean
    public Supplier<CraftingProfitService> craftingProfitServiceFactory() {
        return CraftingProfitService::new;
    }

    /**
     * Supplies a fresh {@link CraftingDiscoveryService} per request, for the same reason
     * (STORY-API-002). Discovery additionally keeps its previous reload's lookup state when a
     * reload finds nothing to discover, so a shared instance could answer an empty request with an
     * earlier one's data.
     */
    @Bean
    public Supplier<CraftingDiscoveryService> craftingDiscoveryServiceFactory() {
        return CraftingDiscoveryService::new;
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
    public GlobalDataRefreshService globalDataRefreshService() {
        return new GlobalDataRefreshService();
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
