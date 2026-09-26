package web;

import application.BankContentsService;
import application.MaterialStorageService;
import application.icons.ItemIconUrls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import repo.BankRepository;
import repo.MaterialStorageRepository.MaterialStorageRow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * STORY-API-007 acceptance criteria 1-3 on real data: proves each account read endpoint reports
 * exactly what its application service returned in process - same slots, same categories, same
 * order, same quantities, same empty slots, same null display metadata, nothing dropped, added or
 * regrouped. Fixture-based contract tests cannot prove that against the real bank/material rows,
 * which is the point of this check (TEST_STRATEGY.md §35.2).
 *
 * <p>Both reads are read-only and no synchronization runs in between, so a difference means the
 * mapping changed the result, not that the data moved. Nothing here writes to the database.
 *
 * <p>Excluded from the default {@code ./mvnw test} run by its {@code IT} suffix. Run explicitly:
 * {@code ./mvnw test -Dtest=AccountReadApiRealDbEquivalenceIT -DfailIfNoSpecifiedTests=false}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountReadApiRealDbEquivalenceIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void bankResponseMatchesTheInProcessRead() throws Exception {
        JsonNode response = JSON.readTree(getOk("/api/account/bank"));
        List<BankRepository.BankSlotRow> expected = new BankContentsService().getBankContents();

        assertEquals(expected.size(), response.path("slotCount").asInt(), "slotCount");
        JsonNode slots = response.path("slots");
        assertEquals(expected.size(), slots.size(), "the API dropped or added bank slots");

        int emptySlots = 0;
        int withMetadata = 0;
        for (int i = 0; i < expected.size(); i++) {
            BankRepository.BankSlotRow row = expected.get(i);
            JsonNode slot = slots.get(i);
            String where = "bank slot index " + i + " (slot " + row.slot() + ")";

            assertEquals(row.slot(), slot.path("slot").asInt(), where + ": slot number/order");
            assertEquals(row.itemId(), nullableInt(slot, "itemId"), where + ": itemId");
            assertEquals(row.count(), nullableInt(slot, "count"), where + ": count");
            assertEquals(ItemIconUrls.iconUrlFor(row.itemId(), row.iconUrl()), nullableText(slot, "iconUrl"),
                    where + ": iconUrl derived from the persisted metadata");
            if (nullableText(slot, "iconUrl") != null) withMetadata++;
            assertEquals(row.rarity(), nullableText(slot, "rarity"), where + ": rarity");

            if (row.itemId() == null) emptySlots++;
        }

        assertFalse(response.toString().contains("render.guildwars2.com"),
                "no upstream URL may reach the browser");

        // Reported so a run's output says whether the real bank actually exercised the empty-slot path
        // and how much of it has usable icon metadata (STORY-API-009 evidence).
        System.out.println(expected.size() + " bank slots (" + emptySlots + " empty, " + withMetadata
                + " with an icon URL) equivalent between HTTP and in-process calls");
    }

    @Test
    void materialsResponseMatchesTheInProcessRead() throws Exception {
        JsonNode response = JSON.readTree(getOk("/api/account/materials"));
        List<MaterialStorageService.MaterialCategory> expected =
                new MaterialStorageService().getMaterialStorage();

        assertEquals(expected.size(), response.path("categoryCount").asInt(), "categoryCount");
        JsonNode categories = response.path("categories");
        assertEquals(expected.size(), categories.size(), "the API dropped or added material categories");

        int stacks = 0;
        int withMetadata = 0;
        for (int c = 0; c < expected.size(); c++) {
            MaterialStorageService.MaterialCategory category = expected.get(c);
            JsonNode actual = categories.get(c);
            String where = "material category index " + c + " (" + category.name() + ")";

            assertEquals(category.name(), actual.path("name").asText(), where + ": label/order");
            JsonNode materials = actual.path("materials");
            assertEquals(category.materials().size(), materials.size(), where + ": stack count");

            for (int m = 0; m < category.materials().size(); m++) {
                MaterialStorageRow row = category.materials().get(m);
                JsonNode stack = materials.get(m);
                String stackWhere = where + " stack " + m;

                assertEquals(row.category(), stack.path("category").asInt(), stackWhere + ": category id");
                assertEquals(row.itemId(), nullableInt(stack, "itemId"), stackWhere + ": itemId");
                assertEquals(row.count(), stack.path("count").asInt(), stackWhere + ": count");
                assertEquals(ItemIconUrls.iconUrlFor(row.itemId(), row.iconUrl()),
                        nullableText(stack, "iconUrl"), stackWhere + ": iconUrl derived from the persisted metadata");
                if (nullableText(stack, "iconUrl") != null) withMetadata++;
                assertEquals(row.rarity(), nullableText(stack, "rarity"), stackWhere + ": rarity");
                stacks++;
            }
        }

        assertFalse(response.toString().contains("render.guildwars2.com"),
                "no upstream URL may reach the browser");

        System.out.println(expected.size() + " material categories / " + stacks + " stacks ("
                + withMetadata + " with an icon URL) equivalent between HTTP and in-process calls");
    }

    private String getOk(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), path + ": unexpected status - " + response.body());
        return response.body();
    }

    /** A JSON null must compare as the row's null, not as 0 - that distinction is the contract. */
    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asInt();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
