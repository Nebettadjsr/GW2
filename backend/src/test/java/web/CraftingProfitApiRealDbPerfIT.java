package web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * STORY-API-001 acceptance criteria 5 and 6: measures the Crafting Profit endpoint against the
 * developer's real PostgreSQL database, with the real application service and the real embedded
 * server - no {@code gw2tool.test.schema} override, so {@code repo.Db#open()} reaches the same
 * database the JavaFX application uses.
 *
 * <p>Each measurement is wall-clock from issuing the HTTP request to holding the complete response
 * body as a string, so it includes routing, the full calculation and complete JSON serialization
 * plus transfer. Context/server startup happens before the timer, which is correct for a
 * per-request figure but means this is <em>backend request</em> time only: per
 * TARGET_ARCHITECTURE.md §33 it is one part of the seven-second navigation-to-complete-page
 * budget, never proof of it.
 *
 * <p>Prints timings, row counts and response sizes; asserts nothing, matching
 * {@code application.CraftingProfitServiceRealDbPerfIT} - its purpose is evidence, not pass/fail.
 * Excluded from the default {@code ./mvnw test} run by its {@code IT} suffix
 * (TEST_STRATEGY.md §34).
 *
 * <p>Run explicitly: {@code ./mvnw test -Dtest=CraftingProfitApiRealDbPerfIT -DfailIfNoSpecifiedTests=false}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CraftingProfitApiRealDbPerfIT {

    /** Empty body: the documented defaults, i.e. the default All scope with the view's settings. */
    private static final String DEFAULT_ALL_REQUEST = "{}";

    /**
     * The same default scope with STORY-DOM-021's material rule switched off: the one request shape
     * that also loads the Trading Post tradeability classification and restricts paths with it. Its
     * figures are recorded beside the default so the option's cost is visible (DOMAIN_SPEC.md §2.1.1);
     * the default above issues no classification query at all.
     */
    private static final String DEFAULT_REQUEST =
            "{}";

    private static final int REPEAT_REQUESTS = 3;

    @LocalServerPort
    private int port;

    @Test
    void reportsRealDatabaseRequestTimingsForTheDefaultAllScope() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        URI endpoint = URI.create("http://localhost:" + port + "/api/crafting/profit");

        System.out.println("=== STORY-API-001 API request timing (real DB, default All scope) ===");
        System.out.println("Endpoint: POST " + endpoint + "  body: " + DEFAULT_ALL_REQUEST);

        List<Long> timings = new ArrayList<>();
        timings.add(measure(client, endpoint, DEFAULT_ALL_REQUEST, "FIRST (cold JVM/JIT, cold caches)"));
        for (int i = 1; i <= REPEAT_REQUESTS; i++) {
            timings.add(measure(client, endpoint, DEFAULT_ALL_REQUEST, "REPEAT " + i));
        }

        long max = timings.stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.println("MAXIMUM request time: " + max + " ms"
                + "  (backend request only; the §33 seven-second budget also covers transport to a"
                + " browser and complete page rendering)");
    }

    @Test
    void reportsRealDatabaseRequestTimingsWithNonTradingPostMaterialsExcluded() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        URI endpoint = URI.create("http://localhost:" + port + "/api/crafting/profit");

        System.out.println("=== STORY-DOM-021 API request timing (real DB, All scope, "
                + "default settings) ===");
        System.out.println("Endpoint: POST " + endpoint + "  body: " + DEFAULT_REQUEST);

        List<Long> timings = new ArrayList<>();
        timings.add(measure(client, endpoint, DEFAULT_REQUEST,
                "FIRST (cold JVM/JIT, cold caches)"));
        for (int i = 1; i <= REPEAT_REQUESTS; i++) {
            timings.add(measure(client, endpoint, DEFAULT_REQUEST, "REPEAT " + i));
        }

        long max = timings.stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.println("MAXIMUM request time: " + max + " ms"
                + "  (backend request only, one classification query included; not §33 page proof)");
    }

    private long measure(HttpClient client, URI endpoint, String requestBody, String label) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        String body = response.body();
        String detail;
        if (response.statusCode() == 200) {
            JsonNode root = new ObjectMapper().readTree(body);
            detail = "rows=" + root.path("rowCount").asInt()
                    + " responseBytes=" + body.getBytes(StandardCharsets.UTF_8).length;
        } else {
            detail = "UNEXPECTED STATUS - body=" + body;
        }

        System.out.println(label + ": " + elapsedMs + " ms (HTTP " + response.statusCode() + ", " + detail + ")");
        return elapsedMs;
    }
}
