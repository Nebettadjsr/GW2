package support.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import repo.CraftingGraphCache;
import repo.Db;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Default-CI owner of complete Bank and Materials HTTP parity (TEST_STRATEGY.md §35.2), replacing the
 * retired owner-database {@code AccountReadApiRealDbEquivalenceIT}. The real application, authenticated
 * account guard, scoped services and scoped SQL run over a disposable Flyway schema holding the synthetic
 * fixtures plus extra rows that make the interesting cases present: an empty slot, a zero count, an item with
 * no metadata, binding restrictions, an accepted and a rejected icon source, a category whose catalog
 * position has no stored quantity, and a category with no positions.
 *
 * <p>Expected payloads are written out by hand from the loaded fixture rows - never produced by a service or
 * a production DTO mapper - and compared field by field and in order, with JSON null kept distinct from
 * zero. Accounts A and B overlap on item ids 1 and 3 and on the shared catalog, so a leaked row or a
 * misattributed quantity changes a hand-written value.
 */
@SpringBootTest(classes = web.Gw2ApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gw2.account-access.enforce=true",
                "gw2.global-refresh.initial-delay-ms=3600000",
                "gw2.owner.account-guid=" + SyntheticFixtures.A, "gw2.test.suite=account-read-parity"})
@Import(PopulatedFixtureHttpTest.Proofs.class)
class AccountReadHttpParityTest {

    static final String SCHEMA = "kpi_read_" + UUID.randomUUID().toString().replace("-", "");
    static final ObjectMapper JSON = new ObjectMapper();

    private static final String ICON_1 = "https://render.guildwars2.com/file/AAAA1111/1001.png";
    private static final String ICON_2 = "https://render.guildwars2.com/file/BBBB2222/1002.jpg";

    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void load() throws Exception {
        PopulatedFixtureIsolationIT.createSchema(SCHEMA);
        FixtureSnapshot base = SyntheticFixtures.build();
        Map<String, List<ObjectNode>> t = new LinkedHashMap<>();
        for (String name : FixtureSnapshot.ALL_TABLES) t.put(name, new ArrayList<>(base.rows(name)));
        for (ObjectNode item : t.get("items")) {
            switch (item.path("item_id").asInt()) {
                case 1 -> item.put("icon_url", ICON_1);
                case 2 -> item.put("icon_url", ICON_2);
                case 3 -> item.put("icon_url", "http://example.invalid/not-render.png"); // rejected source
                case 200 -> item.put("no_mystic_forge", true);
                default -> { }
            }
        }
        // Account A: slots inserted out of order; 3 empty, 4 character-bound bag, 5 item without metadata, 6 zero count.
        t.get("account_bank").add(bank(SyntheticFixtures.A, 6, 3, 0, null, null));
        t.get("account_bank").add(bank(SyntheticFixtures.A, 4, 200, 1, "Character", "Aria"));
        t.get("account_bank").add(bank(SyntheticFixtures.A, 3, null, null, null, null));
        t.get("account_bank").add(bank(SyntheticFixtures.A, 5, 999, 4, null, null));
        for (ObjectNode r : t.get("account_bank")) {
            if (r.path("account_id").asText().equals(SyntheticFixtures.A) && r.path("slot").asInt() == 1) r.put("binding", "Account");
        }
        // Shared catalog: a leading category whose positions nobody stores, and one without positions.
        t.get("material_categories").add(SyntheticFixtures.row("category_id", 6, "name", "Lead", "display_order", 0));
        t.get("material_category_items").add(SyntheticFixtures.row("category_id", 6, "position", 1, "item_id", 100));
        t.get("material_category_items").add(SyntheticFixtures.row("category_id", 6, "position", 2, "item_id", 101));
        t.get("material_categories").add(SyntheticFixtures.row("category_id", 7, "name", "Empty", "display_order", 2));
        try (Connection c = PopulatedFixtureIsolationIT.connect()) {
            FixtureLoader.load(c, SCHEMA, new FixtureSnapshot(base.kind(), base.version(), t, base.expected(),
                    base.sourceRevision(), null));
        }
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, SCHEMA);
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, cacheFile().toString());
    }

    private static ObjectNode bank(String account, int slot, Integer item, Integer count, String binding, String boundTo) {
        return SyntheticFixtures.row("account_id", account, "slot", slot, "item_id", item, "count", count,
                "binding", binding, "bound_to", boundTo, "fetched_at", SyntheticFixtures.FRESH);
    }

    private static java.nio.file.Path cacheFile() {
        return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), SCHEMA + "-graph.json");
    }

    @AfterEach
    void noLiveEdgeAndNoWorkerLeft() throws Exception {
        PopulatedFixtureHttpTest.drain();
        assertEquals(List.of(), PopulatedFixtureHttpTest.UNEXPECTED, "no external edge may be reached by a read");
    }

    @AfterAll
    static void cleanup() throws Exception {
        PopulatedFixtureHttpTest.drain();
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        System.clearProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        java.nio.file.Files.deleteIfExists(cacheFile());
        PopulatedFixtureIsolationIT.drop(SCHEMA);
    }

    /** The canonical, application-owned icon URL for an accepted source: SHA-256 of that source URL. */
    private static String icon(int itemId, String source, String extension) throws Exception {
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        return "/api/items/" + itemId + "/icon/" + key + "." + extension;
    }

    private String get(String account, String path) throws Exception {
        HttpResponse<String> login = send("POST", "/api/session", "{}", SyntheticFixtures.keyOf(account), null, null);
        assertEquals(200, login.statusCode(), "synthetic login");
        String set = login.headers().firstValue("Set-Cookie").orElseThrow();
        HttpResponse<String> r = send("GET", path, null, null, set.substring(0, set.indexOf(';')),
                login.headers().firstValue("X-Account-Context").orElseThrow());
        assertEquals(200, r.statusCode(), path + ": " + r.body());
        assertFalse(r.body().contains("render.guildwars2.com"), "no upstream URL may reach the browser");
        return r.body();
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String cookie, String context) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", "http://localhost:" + port).header("Content-Type", "application/json");
        if (key != null) b.header("X-GW2-API-Key", key);
        if (cookie != null) b.header("Cookie", cookie).header("X-Account-Context", context);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** One expected slot: JSON null is {@code null}, never 0, and every projected field is listed. */
    private record Slot(int slot, Integer itemId, Integer count, String iconUrl, String rarity, boolean selectable,
                        String itemName, String binding, String boundTo) {}

    private static void assertBank(String who, List<Slot> expected, String body) throws Exception {
        JsonNode bank = JSON.readTree(body);
        assertEquals(expected.size(), bank.path("slotCount").asInt(), who + " slotCount");
        JsonNode slots = bank.path("slots");
        assertEquals(expected.size(), slots.size(), who + " slots");
        for (int i = 0; i < expected.size(); i++) {
            Slot e = expected.get(i);
            JsonNode s = slots.get(i);
            String at = who + " slot index " + i;
            assertEquals(e.slot, s.path("slot").asInt(), at + " slot");
            assertEquals(e.itemId, nullableInt(s, "itemId"), at + " itemId");
            assertEquals(e.count, nullableInt(s, "count"), at + " count");
            assertEquals(e.iconUrl, nullableText(s, "iconUrl"), at + " iconUrl");
            assertEquals(e.rarity, nullableText(s, "rarity"), at + " rarity");
            assertEquals(e.selectable, s.path("protectionSelectable").asBoolean(), at + " protectionSelectable");
            assertEquals(e.itemName, nullableText(s, "itemName"), at + " itemName");
            assertEquals(e.binding, nullableText(s, "binding"), at + " binding");
            assertEquals(e.boundTo, nullableText(s, "boundTo"), at + " boundTo");
            assertFalse(s.has("iconPath"), at + ": the backend filesystem path must not be exposed");
        }
    }

    @Test
    void bankProjectsEverySlotFieldInSlotOrderPerAccount() throws Exception {
        String icon1 = icon(1, ICON_1, "png");
        String icon2 = icon(2, ICON_2, "jpg");
        assertBank("A", List.of(
                new Slot(0, 1, 50, icon1, "Basic", true, "Fixture Material 1", null, null),
                new Slot(1, 2, 30, icon2, "Basic", true, "Fixture Material 2", "Account", null),
                new Slot(2, 100, 5, null, "Fine", true, "Fixture Dish", null, null),
                // Empty slot: kept in place, every item field JSON null.
                new Slot(3, null, null, null, null, true, null, null, null),
                // Character-bound bag, flagged no-Mystic-Forge and used by no recipe or material list.
                new Slot(4, 200, 1, null, "Basic", false, "Fixture Bag", "Character", "Aria"),
                // Stored item without any item row: count kept, display metadata null.
                new Slot(5, 999, 4, null, null, true, null, null, null),
                // Zero count is 0, distinct from the empty slot's null; its icon source is rejected.
                new Slot(6, 3, 0, null, "Basic", true, "Fixture Material 3", null, null)),
                get(SyntheticFixtures.A, "/api/account/bank"));
        // B overlaps item ids 1 and 3 with different counts and none of A's extra metadata or restrictions.
        assertBank("B", List.of(
                new Slot(0, 1, 7, icon1, "Basic", true, "Fixture Material 1", null, null),
                new Slot(1, 3, 900, null, "Basic", true, "Fixture Material 3", null, null)),
                get(SyntheticFixtures.B, "/api/account/bank"));
    }

    private record Stack(int position, int itemId, int count, String iconUrl, String rarity, String itemName) {}

    private record Category(int id, String name, int order, List<Stack> materials) {}

    private static void assertMaterials(String who, List<Category> expected, String body) throws Exception {
        JsonNode response = JSON.readTree(body);
        assertEquals(expected.size(), response.path("categoryCount").asInt(), who + " categoryCount");
        JsonNode categories = response.path("categories");
        assertEquals(expected.size(), categories.size(), who + " categories");
        for (int c = 0; c < expected.size(); c++) {
            Category e = expected.get(c);
            JsonNode actual = categories.get(c);
            String at = who + " category index " + c;
            assertEquals(e.id, actual.path("category").asInt(), at + " id");
            assertEquals(e.name, actual.path("name").asText(), at + " name");
            assertEquals(e.order, actual.path("order").asInt(), at + " order");
            assertTrue(actual.path("materials").isArray(), at + " materials is an array even when empty");
            assertEquals(e.materials.size(), actual.path("materials").size(), at + " stack count");
            for (int m = 0; m < e.materials.size(); m++) {
                Stack s = e.materials.get(m);
                JsonNode n = actual.path("materials").get(m);
                String where = at + " stack " + m;
                assertEquals(s.position, n.path("position").asInt(), where + " position");
                assertEquals(s.itemId, n.path("itemId").asInt(), where + " itemId");
                assertTrue(n.path("count").isInt(), where + " count is a number, never null");
                assertEquals(s.count, n.path("count").asInt(), where + " count");
                assertEquals(s.iconUrl, nullableText(n, "iconUrl"), where + " iconUrl");
                assertEquals(s.rarity, nullableText(n, "rarity"), where + " rarity");
                assertEquals(s.itemName, nullableText(n, "itemName"), where + " itemName");
            }
        }
    }

    @Test
    void materialsGroupTheSharedCatalogByCategoryAndPositionWithEachAccountsOwnQuantities() throws Exception {
        String icon1 = icon(1, ICON_1, "png");
        String icon2 = icon(2, ICON_2, "jpg");
        // Catalog order is display_order, so "Lead" (0) precedes "Fixture" (1) and "Empty" (2) closes the list.
        // A stores items 1-4 only; catalog positions 5 and 6 and all of "Lead" are zero, not absent or null.
        assertMaterials("A", List.of(
                new Category(6, "Lead", 0, List.of(
                        new Stack(1, 100, 0, null, "Fine", "Fixture Dish"),
                        new Stack(2, 101, 0, null, "Fine", "Fixture Feast"))),
                new Category(5, "Fixture", 1, List.of(
                        new Stack(1, 1, 200, icon1, "Basic", "Fixture Material 1"),
                        new Stack(2, 2, 150, icon2, "Basic", "Fixture Material 2"),
                        new Stack(3, 3, 40, null, "Basic", "Fixture Material 3"),
                        new Stack(4, 4, 10, null, "Basic", "Fixture Material 4"),
                        new Stack(5, 5, 0, null, "Basic", "Fixture Material 5"),
                        new Stack(6, 6, 0, null, "Basic", "Fixture Material 6"))),
                new Category(7, "Empty", 2, List.of())),
                get(SyntheticFixtures.A, "/api/account/materials"));
        // B stores items 1, 2 and 5: same ids as A at different quantities, and item 5 only B has.
        assertMaterials("B", List.of(
                new Category(6, "Lead", 0, List.of(
                        new Stack(1, 100, 0, null, "Fine", "Fixture Dish"),
                        new Stack(2, 101, 0, null, "Fine", "Fixture Feast"))),
                new Category(5, "Fixture", 1, List.of(
                        new Stack(1, 1, 5, icon1, "Basic", "Fixture Material 1"),
                        new Stack(2, 2, 9, icon2, "Basic", "Fixture Material 2"),
                        new Stack(3, 3, 0, null, "Basic", "Fixture Material 3"),
                        new Stack(4, 4, 0, null, "Basic", "Fixture Material 4"),
                        new Stack(5, 5, 77, null, "Basic", "Fixture Material 5"),
                        new Stack(6, 6, 0, null, "Basic", "Fixture Material 6"))),
                new Category(7, "Empty", 2, List.of())),
                get(SyntheticFixtures.B, "/api/account/materials"));
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asInt();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
