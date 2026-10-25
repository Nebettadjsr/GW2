package web;

import application.BankContentsService;
import application.icons.ItemIconUrls;
import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import repo.BankRepository;
import repo.Db;
import repo.EnvConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-API-009 against the real database: the image route answers the URLs the real persisted
 * metadata actually produces, with the documented headers, and it never redirects a browser upstream.
 *
 * <p>Read-only with respect to the database and deliberately narrow: it samples the first few bank
 * items that carry retained metadata and asks for their own URLs. <strong>No synchronization runs</strong> -
 * neither metadata nor icon download is triggered here, so the coverage this prints is whatever the
 * database already holds. On a cold cache a sampled request may perform one bounded upstream image
 * fetch and publish it into the configured {@code ICON_CACHE_DIR}, which is the endpoint's normal
 * behavior; the assertions therefore accept 200, 404 and 503 and report which occurred rather than
 * requiring a successful delivery. Fixture-based tests own the per-outcome behavior.
 *
 * <p>Excluded from the default {@code ./mvnw test} run by its {@code IT} suffix. Run explicitly:
 * {@code ./mvnw test -Dtest=ItemIconApiRealDbIT -DfailIfNoSpecifiedTests=false}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ItemIconApiRealDbIT {

    private static final int SAMPLE_LIMIT = 3;
    private static final String FOREIGN_KEY =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String RETAINED_SOURCE =
            "https://render.guildwars2.com/file/1111111111111111111111111111111111111111111111111111111111111111/19721.png";

    private final String schema = "test_item_icon_real_db_" + UUID.randomUUID().toString().replace("-", "");
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void createDisposableManagedSchemaWithScopedBankRows() throws Exception {
        try (Connection con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
             Statement statement = con.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        try (Connection con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"))) {
            con.setSchema(schema);
            try (Statement statement = con.createStatement()) {
                statement.execute("INSERT INTO items(item_id, name, icon_url) VALUES "
                        + "(19721, 'icon fixture one', '" + RETAINED_SOURCE + "'), "
                        + "(19722, 'icon fixture two', '" + RETAINED_SOURCE + "'), "
                        + "(19723, 'icon fixture three', '" + RETAINED_SOURCE + "')");
                statement.execute("INSERT INTO account_registry(account_id) VALUES ('" + accountId + "')");
                statement.execute("INSERT INTO account_bank(account_id, slot, item_id, count) VALUES "
                        + "('" + accountId + "', 0, 19721, 1), ('" + accountId + "', 1, 19722, 1), "
                        + "('" + accountId + "', 2, 19723, 1)");
            }
        }
    }

    @AfterEach
    void dropDisposableManagedSchema() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        try (Connection con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
             Statement statement = con.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @LocalServerPort
    private int port;

    @Test
    void theUrlsTheRealBankReadEmitsAreAnsweredWithTheDocumentedHeaders() throws Exception {
        List<BankRepository.BankSlotRow> slots = new BankContentsService()
                .getBankContents(new AccountScope(accountId));
        assertEquals(3, slots.size(), "the disposable fixture must exercise representative bank rows");

        List<String> sampled = new ArrayList<>();
        int withMetadata = 0;
        for (BankRepository.BankSlotRow slot : slots) {
            String url = ItemIconUrls.iconUrlFor(slot.itemId(), slot.iconUrl());
            if (url == null) continue;

            withMetadata++;
            if (sampled.size() < SAMPLE_LIMIT && !sampled.contains(url)) sampled.add(url);
        }

        System.out.println(slots.size() + " bank slots, " + withMetadata
                + " with an accepted retained icon source; sampling " + sampled.size());

        for (String url : sampled) {
            HttpResponse<byte[]> response = get(url, null);
            int status = response.statusCode();

            assertTrue(status == 200 || status == 404 || status == 503,
                    url + ": unexpected status " + status);
            assertTrue(response.headers().firstValue("Location").isEmpty(),
                    url + ": an image response must never redirect the browser");

            if (status == 200) {
                assertDeliveredCorrectly(url, response);
            } else {
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null),
                        url + ": a failure must not be stored by the browser");
            }

            System.out.println("  " + url + " -> " + status);
        }
    }

    @Test
    void aForeignKeyForARealItemIsANotFoundWithoutRedirectOrUpstreamUrl() throws Exception {
        List<BankRepository.BankSlotRow> slots = new BankContentsService()
                .getBankContents(new AccountScope(accountId));
        Integer itemId = slots.stream()
                .filter(slot -> slot.itemId() != null)
                .map(BankRepository.BankSlotRow::itemId)
                .findFirst()
                .orElse(null);

        assertNotNull(itemId, "the disposable fixture must supply a bank item for the foreign-key probe");

        HttpResponse<byte[]> response = get("/api/items/" + itemId + "/icon/" + FOREIGN_KEY + ".png", null);

        assertEquals(404, response.statusCode(), "a key the item's source does not derive must be a 404");
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        assertFalse(new String(response.body()).contains("render.guildwars2.com"),
                "no upstream URL may reach the browser");
    }

    @Test
    void aMalformedRouteIsABadRequest() throws Exception {
        assertEquals(400, get("/api/items/19721/icon/not-a-key.png", null).statusCode());
        assertEquals(400, get("/api/items/abc/icon/" + FOREIGN_KEY + ".png", null).statusCode());
        assertEquals(400, get("/api/items/19721/icon/" + FOREIGN_KEY + ".gif", null).statusCode());
    }

    private void assertDeliveredCorrectly(String url, HttpResponse<byte[]> response) throws Exception {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.equals("image/png") || contentType.equals("image/jpeg"),
                url + ": unexpected content type " + contentType);
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("max-age=86400"),
                url + ": missing the documented freshness");
        assertTrue(response.body().length > 0, url + ": an empty body is never a successful image");

        String etag = response.headers().firstValue("ETag").orElse(null);
        assertNotNull(etag, url + ": a successful image must carry a strong validator");

        HttpResponse<byte[]> conditional = get(url, etag);
        assertEquals(304, conditional.statusCode(), url + ": a matching validator must be a 304");
        assertEquals(etag, conditional.headers().firstValue("ETag").orElse(null));
        assertTrue(conditional.headers().firstValue("Cache-Control").orElse("").contains("max-age=86400"),
                url + ": a 304 must retain the freshness");
        assertEquals(0, conditional.body().length, url + ": a 304 carries no body");
    }

    private HttpResponse<byte[]> get(String path, String ifNoneMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch);

        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
