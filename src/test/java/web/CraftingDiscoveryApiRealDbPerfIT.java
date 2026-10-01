package web;

import application.CharacterSelectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import repo.CharacterRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * STORY-API-002 acceptance criterion 4: measures the Crafting Discovery endpoint against the
 * developer's real PostgreSQL database, with the real application service and the real embedded
 * server - no {@code gw2tool.test.schema} override, so {@code repo.Db#open()} reaches the same
 * database the JavaFX application uses. The measurement is what decides the §23 / UD-007
 * synchronous-versus-asynchronous question for this route; it is not a performance target and not
 * an optimization campaign.
 *
 * <p>The scope is read from the real database rather than hard-coded: the first synced crafting
 * discipline+character combination, i.e. what the JavaFX Discovery page itself selects on opening
 * (CURRENT_ARCHITECTURE.md §5.2). The default settings are Discovery's own opening settings, so
 * this measures the page-load-equivalent request.
 *
 * <p>Each measurement is wall-clock from issuing the HTTP request to holding the complete response
 * body as a string, so it includes routing, the full calculation and complete JSON serialization
 * plus transfer. Context/server startup happens before the timer, which is correct for a
 * per-request figure and is stated as such.
 *
 * <p>Prints timings, row counts and response sizes; asserts nothing, matching
 * {@code CraftingProfitApiRealDbPerfIT} - its purpose is evidence, not pass/fail. Excluded from the
 * default {@code ./mvnw test} run by its {@code IT} suffix (TEST_STRATEGY.md §35.2).
 *
 * <p>Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingDiscoveryApiRealDbPerfIT -DfailIfNoSpecifiedTests=false}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CraftingDiscoveryApiRealDbPerfIT {

    private static final int REPEAT_REQUESTS = 3;

    @LocalServerPort
    private int port;

    @Test
    void reportsRealDatabaseRequestTimingsForTheFirstSyncedCraftingCharacter() throws Exception {
        List<CharacterRepository.DiscRow> options = new CharacterSelectionService().getCraftingCharacterOptions();
        if (options.isEmpty()) {
            System.out.println("=== STORY-API-002 API request timing: SKIPPED - "
                    + "no synced crafting character in the real database, so no Discovery scope exists ===");
            return;
        }

        CharacterRepository.DiscRow scope = options.get(0);
        String requestBody = """
                {"scope": {"discipline": "%s", "characterName": "%s", "rating": %d}}"""
                .formatted(scope.discipline, scope.charName, scope.rating);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        URI endpoint = URI.create("http://localhost:" + port + "/api/crafting/discovery");

        System.out.println("=== STORY-API-002 API request timing (real DB, Discovery default settings) ===");
        System.out.println("Scope: " + scope.discipline + " lvl " + scope.rating + " - " + scope.charName
                + "  (of " + options.size() + " synced crafting combinations)");
        System.out.println("Endpoint: POST " + endpoint + "  body: " + requestBody.replace('\n', ' '));

        List<Long> timings = new ArrayList<>();
        timings.add(measure(client, endpoint, requestBody, "FIRST (cold JVM/JIT, cold caches)"));
        for (int i = 1; i <= REPEAT_REQUESTS; i++) {
            timings.add(measure(client, endpoint, requestBody, "REPEAT " + i));
        }

        // One request per remaining combination, so the maximum below is a real worst case across
        // the disciplines/ratings this account can actually ask for, not one favourable scope.
        System.out.println("--- one request per other synced combination (warm) ---");
        for (int i = 1; i < options.size(); i++) {
            CharacterRepository.DiscRow other = options.get(i);
            String otherBody = """
                    {"scope": {"discipline": "%s", "characterName": "%s", "rating": %d}}"""
                    .formatted(other.discipline, other.charName, other.rating);
            timings.add(measure(client, endpoint, otherBody,
                    other.discipline + " lvl " + other.rating + " - " + other.charName));
        }

        long max = timings.stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.println("MAXIMUM request time: " + max + " ms  (backend request only; server startup"
                + " is outside this timer)");
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
