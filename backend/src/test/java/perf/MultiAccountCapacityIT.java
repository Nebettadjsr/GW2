package perf;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated investigation #116. Real HTTP/SQL with distinct synthetic accounts, never live ArenaNet. */
class MultiAccountCapacityIT extends ApplicationBenchmarkHarness {
    record Browser(String key, String cookie, String context, int marker) { }
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final List<Browser> browsers = new ArrayList<>();

    @Override String fixtureVersion() { return "multiaccount-fixtures-1"; }

    @Override Sizes sizes() {
        int recipes = Integer.getInteger("gw2.investigation.recipes", 5000);
        return new Sizes(400, 200, recipes, 400, 2, 1, 2, 4, 1000);
    }

    @Test void investigateDistinctAccountCapacity() throws Exception {
        assertTrue(Boolean.getBoolean("gw2.investigation"), "Explicit test-only fixture opt-in required");
        MeasurementReport report = runAll();
        Path dir = Path.of(System.getProperty("gw2.perf.output-dir", "target/investigation"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("multi-account-resources.json"), report.toJsonString());
        Files.writeString(dir.resolve("multi-account-resources.md"), report.toMarkdown());
        for (var workload : report.workloads()) {
            assertTrue(workload.diagnostics().isEmpty(), workload.name() + ": " + workload.diagnostics());
        }
    }

    @Override void additionalWorkloads(MeasurementReport report) throws Exception {
        for (int i = 0; i < 20; i++) browsers.add(login(i));
        for (int count : new int[]{1, 2, 5, 10, 20}) {
            report.add(measure("accounts_onboarding_" + count, count + " distinct accounts concurrently synchronize",
                    "Real HTTP admission, per-account coordinator, publication and PostgreSQL; deterministic upstream.", count,
                    rec -> parallel(count, i -> synchronize(browsers.get(i), rec))));
            seedRosters();
            report.add(measure("accounts_mixed_" + count, count + " isolated accounts: changed-budget Profit plus bank/status/selectors",
                    "HTTP client in application JVM, no browser rendering; deterministic 5000-recipe binary-tree fixture by default.", count * 12,
                    rec -> parallel(count, i -> mixed(browsers.get(i), rec))));
        }
    }

    Browser login(int index) throws Exception {
        String key = "investigation-" + index;
        Browser provisional = new Browser(key, null, null, index + 1000);
        var r = request(provisional, "POST", "/api/session", "{}", true, null);
        assertEquals(200, r.statusCode());
        String cookie = r.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new Browser(key, cookie, r.headers().firstValue("X-Account-Context").orElseThrow(), index + 1000);
    }

    void synchronize(Browser browser, Recorder rec) throws Exception {
        long start = System.nanoTime();
        var accepted = request(browser, "POST", "/api/sync/account", "{}", true, rec);
        assertEquals(202, accepted.statusCode(), accepted.body());
        String id = JSON.readTree(accepted.body()).path("taskId").asText();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (System.nanoTime() < deadline) {
            var response = request(browser, "GET", "/api/sync/tasks/" + id, null, false, rec);
            assertEquals(200, response.statusCode(), response.body());
            String state = JSON.readTree(response.body()).path("state").asText();
            assertNotEquals("FAILED", state, response.body());
            if (state.equals("SUCCEEDED")) {
                rec.jobMillis.add(Stats.nanosToMillis(System.nanoTime() - start));
                assertBank(browser, rec);
                return;
            }
            Thread.sleep(100);
        }
        fail("Account sync did not finish in three minutes");
    }

    void mixed(Browser browser, Recorder rec) throws Exception {
        for (int round = 0; round < 3; round++) {
            var profit = request(browser, "POST", "/api/crafting/profit",
                    "{\"settings\":{\"maxBuyCopper\":" + (12000 + round) + "}}", false, rec);
            assertEquals(200, profit.statusCode(), profit.body());
            JsonNode rows = JSON.readTree(profit.body()).path("rows");
            assertTrue(rows.isArray() && !rows.isEmpty(), "Must exercise actual calculation");
            assertTrue(java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                    .anyMatch(row -> row.path("craftableCount").asInt() > 0), "Must actually craft");
            assertBank(browser, rec);
            assertEquals(200, request(browser, "GET", "/api/system/status", null, false, rec).statusCode());
            assertEquals(200, request(browser, "GET", "/api/crafting/selector-options", null, false, rec).statusCode());
        }
    }

    void assertBank(Browser browser, Recorder rec) throws Exception {
        var r = request(browser, "GET", "/api/account/bank", null, false, rec);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode body = JSON.readTree(r.body());
        JsonNode entries = body.isArray() ? body : body.path("items");
        // DTO shape is verified below rather than silently accepting an empty/default projection.
        if (!entries.isArray()) entries = body.path("slots");
        assertTrue(entries.isArray() && !entries.isEmpty(), r.body());
        assertTrue(java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                .anyMatch(row -> row.path("count").asInt(-1) == browser.marker()), "Distinct-account marker missing: " + r.body());
    }

    void seedRosters() throws Exception {
        writeInTestSchema(c -> {
            for (Browser browser : browsers) {
                String account = fixtureAccount(browser.key());
                try (var p = c.prepareStatement("INSERT INTO account_registry(account_id) VALUES (?::uuid) ON CONFLICT DO NOTHING")) {
                    p.setString(1, account); p.executeUpdate();
                }
                try (var p = c.prepareStatement("INSERT INTO characters(account_id,name,profession) VALUES (?::uuid,'Aria','Guardian') ON CONFLICT DO NOTHING")) {
                    p.setString(1, account); p.executeUpdate();
                }
                try (var p = c.prepareStatement("INSERT INTO character_crafting(account_id,character_id,discipline,rating,is_active) SELECT account_id,character_id,'Chef',400,true FROM characters WHERE account_id=?::uuid ON CONFLICT DO NOTHING")) {
                    p.setString(1, account); p.executeUpdate();
                }
            }
        });
    }

    interface ClientWork { void run(int index) throws Exception; }
    void parallel(int count, ClientWork work) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var pending = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                pending.add(executor.submit(() -> { start.await(); work.run(index); return null; }));
            }
            start.countDown();
            for (var result : pending) result.get(5, TimeUnit.MINUTES);
        }
    }

    HttpResponse<String> request(Browser browser, String method, String path, String body, boolean key, Recorder rec) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofMinutes(2))
                .header("Origin", "http://localhost:" + port).header("Content-Type", "application/json");
        if (browser.cookie() != null) b.header("Cookie", browser.cookie()).header("X-Account-Context", browser.context());
        if (key) b.header("X-GW2-API-Key", browser.key());
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        long start = System.nanoTime();
        var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (rec != null) {
            rec.requests.incrementAndGet();
            rec.latenciesMillis.add(Stats.nanosToMillis(System.nanoTime() - start));
            rec.bytesIn.addAndGet(r.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if (body != null) rec.bytesOut.addAndGet(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if (r.statusCode() / 100 != 2) { rec.errors.incrementAndGet(); rec.diagnostics.add(method + " " + path + " -> " + r.statusCode()); }
        }
        return r;
    }
}
