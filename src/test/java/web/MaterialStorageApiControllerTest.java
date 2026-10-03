package web;

import application.MaterialStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.MaterialStorageRepository.MaterialStorageRow;
import application.icons.ItemIconUrls;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the material-storage endpoint (STORY-API-007, TEST_STRATEGY.md §11): the
 * returned categories and stacks, their order, the empty-storage result, transport isolation and
 * the mapped failures.
 *
 * <p>The application service is replaced by a stub, so these exercise transport behavior only -
 * delegation, DTO copying and status codes. The grouping, the category labels and the fallback
 * label are the service's and stay covered by {@code application.MaterialStorageServiceTest}; these
 * tests only prove the boundary reports whatever grouping it was handed. Equivalence of both paths
 * on real data is covered by {@link AccountReadApiRealDbEquivalenceIT}.
 */
class MaterialStorageApiControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A retained source the canonical policy accepts, and the URL it must produce (STORY-API-009). */
    private static final String LOG_SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final String LOG_ICON_URL =
            "/api/items/19718/icon/50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472.png";

    /** Right shape, wrong host: accepted nowhere, so it must map to null rather than be passed through. */
    private static final String REJECTED_SOURCE = "https://cdn.example.com/file/ABCDEF0123456789/1234.png";

    /** The desktop path the same row still carries for JavaFX; it must never appear in the response. */
    private static final String LOCAL_ICON_PATH = "C:\\icons\\items\\19718.png";

    private MockMvc mockMvcFor(StubMaterialStorageService service) {
        return MockMvcBuilders
                .standaloneSetup(new MaterialStorageApiController(service))
                .setControllerAdvice(new AccountReadApiExceptionHandler())
                .build();
    }

    // ---------- successful reads ----------

    @Test
    void returnsEveryCategoryAndStackInServiceOrderWithQuantitiesAndDisplayMetadata() throws Exception {
        StubMaterialStorageService service = new StubMaterialStorageService(List.of(
                new MaterialStorageService.MaterialCategory(1, "Basic Crafting Materials", 0, List.of(
                        new MaterialStorageRow(1, "Category 1", 1, 0, 19718, 250, LOG_SOURCE, "Basic"),
                        new MaterialStorageRow(1, "Category 1", 1, 0, 19723, 41, null, null))),
                new MaterialStorageService.MaterialCategory(4, "Ascended Materials", 3, List.of(
                        new MaterialStorageRow(4, "Category 4", 4, 0, 46731, 3, REJECTED_SOURCE, "Ascended")))));

        mockMvcFor(service).perform(get("/api/account/materials"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.categoryCount").value(2))
                .andExpect(jsonPath("$.categories.length()").value(2))
                .andExpect(jsonPath("$.categories[0].name").value("Basic Crafting Materials"))
                .andExpect(jsonPath("$.categories[0].materials.length()").value(2))
                .andExpect(jsonPath("$.categories[0].materials[0].position").value(0))
                .andExpect(jsonPath("$.categories[0].materials[0].itemId").value(19718))
                .andExpect(jsonPath("$.categories[0].materials[0].count").value(250))
                .andExpect(jsonPath("$.categories[0].materials[0].iconUrl").value(LOG_ICON_URL))
                .andExpect(jsonPath("$.categories[0].materials[0].rarity").value("Basic"))
                .andExpect(jsonPath("$.categories[0].materials[1].itemId").value(19723))
                .andExpect(jsonPath("$.categories[0].materials[1].count").value(41))
                // a stack whose item has no matching items row keeps its place and reports null metadata
                .andExpect(jsonPath("$.categories[0].materials[1].iconUrl").doesNotExist())
                .andExpect(jsonPath("$.categories[0].materials[1].rarity").doesNotExist())
                .andExpect(jsonPath("$.categories[1].name").value("Ascended Materials"))
                .andExpect(jsonPath("$.categories[1].materials[0].position").value(0))
                .andExpect(jsonPath("$.categories[1].materials[0].itemId").value(46731))
                .andExpect(jsonPath("$.categories[1].materials[0].count").value(3))
                // a retained source the canonical policy rejects is a null URL, not a guess
                .andExpect(jsonPath("$.categories[1].materials[0].iconUrl").doesNotExist());

        assertEquals(1, service.callCount, "the route must read through the application boundary exactly once");
    }

    @Test
    void theServicesCategoryOrderAndFallbackLabelSurviveTheBoundaryUnchanged() throws Exception {
        // The service reports unknown categories last, under its "Category <id>" fallback label;
        // the controller must not re-sort, rename or drop that group.
        StubMaterialStorageService service = new StubMaterialStorageService(List.of(
                new MaterialStorageService.MaterialCategory(5, "Cooking Materials", 4, List.of(
                        new MaterialStorageRow(5, "Category 5", 5, 0, 12134, 12, null, null))),
                new MaterialStorageService.MaterialCategory(77, "Category 77", 8, List.of(
                        new MaterialStorageRow(77, "Category 77", 77, 0, 99999, 5, null, null)))));

        mockMvcFor(service).perform(get("/api/account/materials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].name").value("Cooking Materials"))
                .andExpect(jsonPath("$.categories[1].name").value("Category 77"))
                .andExpect(jsonPath("$.categories[1].materials[0].position").value(0));
    }

    @Test
    void aStackCarriesOnlyTheDocumentedTransportFields() throws Exception {
        StubMaterialStorageService service = new StubMaterialStorageService(List.of(
                new MaterialStorageService.MaterialCategory(1, "Basic Crafting Materials", 0, List.of(
                        new MaterialStorageRow(1, "Category 1", 1, 0, 19718, 250, LOG_SOURCE, "Basic")))));

        String body = mockMvcFor(service).perform(get("/api/account/materials"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode category = JSON.readTree(body).get("categories").get(0);
        List<String> categoryFields = new ArrayList<>();
        category.fieldNames().forEachRemaining(categoryFields::add);
        assertEquals(List.of("category", "name", "order", "materials"), categoryFields,
                "the category must be a transport record, not the application service's own type");

        List<String> stackFields = new ArrayList<>();
        category.get("materials").get(0).fieldNames().forEachRemaining(stackFields::add);
        assertEquals(List.of("position", "itemId", "count", "iconUrl", "rarity"), stackFields,
                "the stack must be a transport record, not a serialized repository row");
        assertFalse(body.contains(LOCAL_ICON_PATH.replace("\\", "\\\\")),
                "the backend filesystem path must not reach the browser: " + body);
        assertFalse(body.contains("render.guildwars2.com"),
                "no upstream URL may reach the browser: " + body);
    }

    @Test
    void officialPositionKeepsItsItemIdAndApplicationIconUrl() throws Exception {
        StubMaterialStorageService service = new StubMaterialStorageService(List.of(
                new MaterialStorageService.MaterialCategory(10, "Other", 9, List.of(
                        new MaterialStorageRow(10, "Other", 9, 0, 999, 4, LOG_SOURCE, "Basic")))));

        String body = mockMvcFor(service).perform(get("/api/account/materials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].materials[0].count").value(4))
                .andReturn().getResponse().getContentAsString();

        JsonNode stack = JSON.readTree(body).get("categories").get(0).get("materials").get(0);
        assertEquals(999, stack.get("itemId").asInt());
        assertEquals(ItemIconUrls.iconUrlFor(999, LOG_SOURCE), stack.get("iconUrl").asText());
    }

    @Test
    void emptyMaterialStorageIsAnEmptySuccess() throws Exception {
        StubMaterialStorageService service = new StubMaterialStorageService(List.of());

        mockMvcFor(service).perform(get("/api/account/materials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryCount").value(0))
                .andExpect(jsonPath("$.categories.length()").value(0));

        assertEquals(1, service.callCount);
    }

    // ---------- mapped failures ----------

    @Test
    void databaseFailureIsMappedToServiceUnavailableWithoutLeakingDetail() throws Exception {
        StubMaterialStorageService service = new StubMaterialStorageService(
                new SQLException("connection to jdbc:postgresql://host/db refused for user gw2 password hunter2"));

        String body = mockMvcFor(service).perform(get("/api/account/materials"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The crafting data store is currently unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("jdbc"), "the response must not carry connection detail: " + body);
        assertFalse(body.contains("hunter2"), "the response must not carry credentials: " + body);
    }

    @Test
    void anyOtherReadFailureIsMappedToInternalServerErrorWithoutLeakingDetail() throws Exception {
        StubMaterialStorageService service = new StubMaterialStorageService(
                new IllegalStateException("query FROM account_materials blew up"));

        String body = mockMvcFor(service).perform(get("/api/account/materials"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("ACCOUNT_READ_FAILED"))
                .andExpect(jsonPath("$.message").value("The account inventory could not be read"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("FROM account_materials"), "the response must not carry SQL: " + body);
        assertFalse(body.contains("IllegalStateException"), "the response must not carry exception text: " + body);
    }

    @Test
    void unknownPathUnderTheRouteIsANotFound() throws Exception {
        StubMaterialStorageService service = new StubMaterialStorageService(List.of());

        mockMvcFor(service).perform(get("/api/account/materials/does-not-exist"))
                .andExpect(status().isNotFound());

        assertEquals(0, service.callCount);
    }

    // ---------- stub ----------

    /**
     * Stands in for the real application service, for the same reason as the Bank stub: the
     * production controller keeps depending on the concrete type the JavaFX Materials view uses.
     */
    private static final class StubMaterialStorageService extends MaterialStorageService {
        private final List<MaterialCategory> categories;
        private final SQLException sqlFailure;
        private final RuntimeException runtimeFailure;
        int callCount;

        StubMaterialStorageService(List<MaterialCategory> categories) {
            this(categories, null, null);
        }

        StubMaterialStorageService(SQLException sqlFailure) {
            this(List.of(), sqlFailure, null);
        }

        StubMaterialStorageService(RuntimeException runtimeFailure) {
            this(List.of(), null, runtimeFailure);
        }

        private StubMaterialStorageService(List<MaterialCategory> categories,
                                           SQLException sqlFailure,
                                           RuntimeException runtimeFailure) {
            this.categories = categories;
            this.sqlFailure = sqlFailure;
            this.runtimeFailure = runtimeFailure;
        }

        @Override
        public List<MaterialCategory> getMaterialStorage() throws SQLException {
            callCount++;
            if (sqlFailure != null) throw sqlFailure;
            if (runtimeFailure != null) throw runtimeFailure;
            return categories;
        }
    }
}
