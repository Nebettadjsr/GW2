package web;

import application.AccountRefreshService;
import application.CraftingDiscoveryService;
import application.CraftingProfitService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import web.task.BackgroundTaskService;

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
}
