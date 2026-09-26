package web;

import application.BankContentsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.BankRepository;

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
 * HTTP contract tests for the account-bank endpoint (STORY-API-007, TEST_STRATEGY.md §11): the
 * returned slots, their order, empty-slot representation, the empty-account result, transport
 * isolation and the mapped failures.
 *
 * <p>The application service is replaced by a stub, so these exercise transport behavior only -
 * delegation, DTO copying and status codes. What the service itself reads stays covered by
 * {@code application.BankContentsServiceTest}, and the equivalence of both paths on real data by
 * {@link AccountReadApiRealDbEquivalenceIT}.
 */
class BankContentsApiControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A retained source the canonical policy accepts, and the URL it must produce (STORY-API-009). */
    private static final String ECTO_SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final String ECTO_ICON_URL =
            "/api/items/19721/icon/50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472.png";

    /** A .jpg source, to prove the served extension follows the source rather than a fixed .png. */
    private static final String VIAL_SOURCE = "https://render.guildwars2.com/file/FEDCBA9876543210/4321.jpg";
    private static final String VIAL_ICON_URL =
            "/api/items/24295/icon/9247ad5f6cd4702a0c12724f8a90235191e8831a61be53d54a138b86e3984f8d.jpg";

    /** Right shape, wrong host: rejected, so it must map to null rather than be passed through. */
    private static final String REJECTED_SOURCE = "https://cdn.example.com/file/ABCDEF0123456789/1234.png";

    /** The desktop path the same row still carries for JavaFX; it must never appear in the response. */
    private static final String LOCAL_ICON_PATH = "C:\\icons\\items\\19721.png";

    private MockMvc mockMvcFor(StubBankContentsService service) {
        return MockMvcBuilders
                .standaloneSetup(new BankContentsApiController(service))
                .setControllerAdvice(new AccountReadApiExceptionHandler())
                .build();
    }

    // ---------- successful reads ----------

    @Test
    void returnsEverySlotInServiceOrderWithItsIdentityQuantityAndDisplayMetadata() throws Exception {
        StubBankContentsService service = new StubBankContentsService(List.of(
                new BankRepository.BankSlotRow(0, 19721, 250, LOCAL_ICON_PATH, ECTO_SOURCE, "Rare"),
                new BankRepository.BankSlotRow(1, 24295, 1, null, VIAL_SOURCE, "Fine"),
                new BankRepository.BankSlotRow(2, 68063, 7, null, null, null)));

        mockMvcFor(service).perform(get("/api/account/bank"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.slotCount").value(3))
                .andExpect(jsonPath("$.slots.length()").value(3))
                .andExpect(jsonPath("$.slots[0].slot").value(0))
                .andExpect(jsonPath("$.slots[0].itemId").value(19721))
                .andExpect(jsonPath("$.slots[0].count").value(250))
                .andExpect(jsonPath("$.slots[0].iconUrl").value(ECTO_ICON_URL))
                .andExpect(jsonPath("$.slots[0].rarity").value("Rare"))
                .andExpect(jsonPath("$.slots[1].slot").value(1))
                .andExpect(jsonPath("$.slots[1].itemId").value(24295))
                .andExpect(jsonPath("$.slots[1].count").value(1))
                .andExpect(jsonPath("$.slots[1].iconUrl").value(VIAL_ICON_URL))
                .andExpect(jsonPath("$.slots[2].slot").value(2))
                .andExpect(jsonPath("$.slots[2].itemId").value(68063))
                .andExpect(jsonPath("$.slots[2].count").value(7))
                // an item with no matching items row keeps its slot and reports null metadata
                .andExpect(jsonPath("$.slots[2].iconUrl").doesNotExist())
                .andExpect(jsonPath("$.slots[2].rarity").doesNotExist());

        assertEquals(1, service.callCount, "the route must read through the application boundary exactly once");
    }

    @Test
    void anEmptySlotIsKeptInPlaceWithNullItemAndCount() throws Exception {
        StubBankContentsService service = new StubBankContentsService(List.of(
                new BankRepository.BankSlotRow(0, 19721, 250, LOCAL_ICON_PATH, ECTO_SOURCE, "Rare"),
                new BankRepository.BankSlotRow(1, null, null, null, null, null),
                new BankRepository.BankSlotRow(2, 24295, 3, null, REJECTED_SOURCE, "Fine")));

        String body = mockMvcFor(service).perform(get("/api/account/bank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotCount").value(3))
                .andExpect(jsonPath("$.slots[1].slot").value(1))
                .andExpect(jsonPath("$.slots[1].itemId").doesNotExist())
                .andExpect(jsonPath("$.slots[1].count").doesNotExist())
                .andExpect(jsonPath("$.slots[2].itemId").value(24295))
                .andReturn().getResponse().getContentAsString();

        JsonNode empty = JSON.readTree(body).get("slots").get(1);
        assertTrue(empty.get("itemId").isNull(), "an empty slot's item must be null, not a substitute id");
        assertTrue(empty.get("count").isNull(), "an empty slot's count must be null, not 0");
        assertTrue(empty.get("iconUrl").isNull(), "an empty slot has no item and therefore no image");
        assertTrue(JSON.readTree(body).get("slots").get(2).get("iconUrl").isNull(),
                "a rejected retained source must produce null, not a passed-through URL");
    }

    @Test
    void aSlotCarriesOnlyTheDocumentedTransportFields() throws Exception {
        StubBankContentsService service = new StubBankContentsService(List.of(
                new BankRepository.BankSlotRow(0, 19721, 250, LOCAL_ICON_PATH, ECTO_SOURCE, "Rare")));

        String body = mockMvcFor(service).perform(get("/api/account/bank"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode slot = JSON.readTree(body).get("slots").get(0);
        List<String> fields = new ArrayList<>();
        slot.fieldNames().forEachRemaining(fields::add);

        assertEquals(List.of("slot", "itemId", "count", "iconUrl", "rarity"), fields,
                "the slot must be a transport record, not a serialized repository row");
        assertFalse(body.contains("C:\\icons"),
                "the backend filesystem path must not reach the browser: " + body);
        assertFalse(body.contains("render.guildwars2.com"),
                "no upstream URL may reach the browser: " + body);
    }

    @Test
    void anAccountWithNoBankRowsIsAnEmptySuccess() throws Exception {
        StubBankContentsService service = new StubBankContentsService(List.of());

        mockMvcFor(service).perform(get("/api/account/bank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotCount").value(0))
                .andExpect(jsonPath("$.slots.length()").value(0));

        assertEquals(1, service.callCount);
    }

    // ---------- mapped failures ----------

    @Test
    void databaseFailureIsMappedToServiceUnavailableWithoutLeakingDetail() throws Exception {
        StubBankContentsService service = new StubBankContentsService(
                new SQLException("connection to jdbc:postgresql://host/db refused for user gw2 password hunter2"));

        String body = mockMvcFor(service).perform(get("/api/account/bank"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The crafting data store is currently unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("jdbc"), "the response must not carry connection detail: " + body);
        assertFalse(body.contains("hunter2"), "the response must not carry credentials: " + body);
    }

    @Test
    void anyOtherReadFailureIsMappedToInternalServerErrorWithoutLeakingDetail() throws Exception {
        StubBankContentsService service = new StubBankContentsService(
                new IllegalStateException("query FROM account_bank blew up"));

        String body = mockMvcFor(service).perform(get("/api/account/bank"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("ACCOUNT_READ_FAILED"))
                .andExpect(jsonPath("$.message").value("The account inventory could not be read"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("FROM account_bank"), "the response must not carry SQL: " + body);
        assertFalse(body.contains("IllegalStateException"), "the response must not carry exception text: " + body);
    }

    @Test
    void unknownPathUnderTheRouteIsANotFound() throws Exception {
        StubBankContentsService service = new StubBankContentsService(List.of());

        mockMvcFor(service).perform(get("/api/account/bank/does-not-exist"))
                .andExpect(status().isNotFound());

        assertEquals(0, service.callCount);
    }

    // ---------- stub ----------

    /**
     * Stands in for the real application service. Subclassing (rather than an interface) keeps the
     * production controller depending on the same concrete type the JavaFX Bank view uses, which is
     * what the story's "keep JavaFX on the same implementation" constraint asks for.
     */
    private static final class StubBankContentsService extends BankContentsService {
        private final List<BankRepository.BankSlotRow> rows;
        private final SQLException sqlFailure;
        private final RuntimeException runtimeFailure;
        int callCount;

        StubBankContentsService(List<BankRepository.BankSlotRow> rows) {
            this(rows, null, null);
        }

        StubBankContentsService(SQLException sqlFailure) {
            this(List.of(), sqlFailure, null);
        }

        StubBankContentsService(RuntimeException runtimeFailure) {
            this(List.of(), null, runtimeFailure);
        }

        private StubBankContentsService(List<BankRepository.BankSlotRow> rows,
                                        SQLException sqlFailure,
                                        RuntimeException runtimeFailure) {
            this.rows = rows;
            this.sqlFailure = sqlFailure;
            this.runtimeFailure = runtimeFailure;
        }

        @Override
        public List<BankRepository.BankSlotRow> getBankContents() throws SQLException {
            callCount++;
            if (sqlFailure != null) throw sqlFailure;
            if (runtimeFailure != null) throw runtimeFailure;
            return rows;
        }
    }
}
