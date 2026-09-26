package web;

import application.CharacterSelectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.CharacterRepository;
import repo.DiscChoice;
import web.dto.CraftingProfitRequest;
import web.dto.CraftingSelectorOptionsResponse;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the crafting selector-options endpoint (STORY-API-006,
 * TEST_STRATEGY.md §11): the returned options, their order, the empty-database result, transport
 * isolation and the mapped failures.
 *
 * <p>The application service is replaced by a stub, so these exercise transport behavior only -
 * delegation, DTO copying and status codes. What the service itself reads stays covered by
 * {@code application.CharacterSelectionServiceTest}, and the JavaFX selectors by
 * {@code CraftingProfitViewSelectorIT}/{@code CraftingDiscoveryViewSelectorIT}.
 */
class CraftingSelectorOptionsApiControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MockMvc mockMvcFor(StubCharacterSelectionService service) {
        return MockMvcBuilders
                .standaloneSetup(new CraftingSelectorOptionsApiController(service))
                .setControllerAdvice(new CraftingSelectorOptionsApiExceptionHandler())
                .build();
    }

    // ---------- successful reads ----------

    @Test
    void returnsTheDefaultScopeTheGenericDisciplinesAndEveryCharacterOptionInServiceOrder() throws Exception {
        StubCharacterSelectionService service = new StubCharacterSelectionService(List.of(
                new CharacterRepository.DiscRow("Alice", "Chef", 400, true),
                new CharacterRepository.DiscRow("Bea", "Chef", 275, false),
                new CharacterRepository.DiscRow("Alice", "Tailor", 150, false)));

        String body = mockMvcFor(service).perform(get("/api/crafting/selector-options"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.defaultScopeKind").value("ALL"))
                .andExpect(jsonPath("$.disciplines.length()").value(9))
                .andExpect(jsonPath("$.disciplines[0]").value("Chef"))
                .andExpect(jsonPath("$.disciplines[1]").value("Huntsman"))
                .andExpect(jsonPath("$.disciplines[8]").value("Scribe"))
                .andExpect(jsonPath("$.characterOptionCount").value(3))
                .andExpect(jsonPath("$.characterOptions.length()").value(3))
                .andExpect(jsonPath("$.characterOptions[0].characterName").value("Alice"))
                .andExpect(jsonPath("$.characterOptions[0].discipline").value("Chef"))
                .andExpect(jsonPath("$.characterOptions[0].rating").value(400))
                .andExpect(jsonPath("$.characterOptions[0].active").value(true))
                .andExpect(jsonPath("$.characterOptions[1].characterName").value("Bea"))
                .andExpect(jsonPath("$.characterOptions[1].rating").value(275))
                .andExpect(jsonPath("$.characterOptions[1].active").value(false))
                .andExpect(jsonPath("$.characterOptions[2].characterName").value("Alice"))
                .andExpect(jsonPath("$.characterOptions[2].discipline").value("Tailor"))
                .andExpect(jsonPath("$.characterOptions[2].rating").value(150))
                .andReturn().getResponse().getContentAsString();

        assertEquals(List.of("Chef", "Huntsman", "Weaponsmith", "Armorsmith", "Artificer",
                        "Tailor", "Leatherworker", "Jeweler", "Scribe"),
                JSON.readValue(body, CraftingSelectorOptionsResponse.class).disciplines(),
                "the generic discipline entries and their order must match the established selector");
        assertEquals(1, service.craftingCallCount,
                "the route must read through the application boundary exactly once");
        assertEquals(0, service.namesCallCount,
                "the Profit scope selector does not need Discovery's character-name list");
    }

    @Test
    void anOptionCarriesOnlyTheDocumentedTransportFields() throws Exception {
        StubCharacterSelectionService service = new StubCharacterSelectionService(List.of(
                new CharacterRepository.DiscRow("Alice", "Chef", 400, true)));

        String body = mockMvcFor(service).perform(get("/api/crafting/selector-options"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode option = JSON.readTree(body).get("characterOptions").get(0);
        List<String> fields = new ArrayList<>();
        option.fieldNames().forEachRemaining(fields::add);

        assertEquals(List.of("characterName", "discipline", "rating", "active"), fields,
                "the option must be a transport record, not a serialized repository row");
    }

    @Test
    void aReturnedOptionCarriesExactlyTheFactsTheProfitScopeContractNeeds() throws Exception {
        StubCharacterSelectionService service = new StubCharacterSelectionService(List.of(
                new CharacterRepository.DiscRow("Nbt Anch", "Armorsmith", 500, true)));

        String body = mockMvcFor(service).perform(get("/api/crafting/selector-options"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CraftingSelectorOptionsResponse response =
                JSON.readValue(body, CraftingSelectorOptionsResponse.class);
        CraftingSelectorOptionsResponse.CharacterOptionDto option = response.characterOptions().get(0);

        // Exactly what a browser would post back to POST /api/crafting/profit for this entry.
        DiscChoice choice = CraftingProfitApiMapper.toEffective(new CraftingProfitRequest(
                        new CraftingProfitRequest.ScopeDto("CHARACTER_DISCIPLINE",
                                option.discipline(), option.characterName(), option.rating()),
                        null))
                .toDiscChoice();

        assertEquals(DiscChoice.Kind.CHAR_DISCIPLINE, choice.kind);
        assertEquals("Armorsmith", choice.discipline);
        assertEquals("Nbt Anch", choice.charName);
        assertEquals(500, choice.rating);
    }

    @Test
    void noSyncedCharactersIsAnEmptySuccessWithTheAllAndGenericEntriesIntact() throws Exception {
        StubCharacterSelectionService service = new StubCharacterSelectionService(List.of());

        mockMvcFor(service).perform(get("/api/crafting/selector-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultScopeKind").value("ALL"))
                .andExpect(jsonPath("$.disciplines.length()").value(9))
                .andExpect(jsonPath("$.characterOptionCount").value(0))
                .andExpect(jsonPath("$.characterOptions.length()").value(0));

        assertEquals(1, service.craftingCallCount);
    }

    // ---------- mapped failures ----------

    @Test
    void databaseFailureIsMappedToServiceUnavailableWithoutLeakingDetail() throws Exception {
        StubCharacterSelectionService service = new StubCharacterSelectionService(
                new SQLException("connection to jdbc:postgresql://host/db refused for user gw2 password hunter2"));

        String body = mockMvcFor(service).perform(get("/api/crafting/selector-options"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The crafting data store is currently unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("jdbc"), "the response must not carry connection detail: " + body);
        assertFalse(body.contains("hunter2"), "the response must not carry credentials: " + body);
    }

    @Test
    void anyOtherReadFailureIsMappedToInternalServerErrorWithoutLeakingDetail() throws Exception {
        StubCharacterSelectionService service = new StubCharacterSelectionService(
                new IllegalStateException("query FROM characters blew up"));

        String body = mockMvcFor(service).perform(get("/api/crafting/selector-options"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("SELECTOR_OPTIONS_FAILED"))
                .andExpect(jsonPath("$.message").value("The crafting selector options could not be read"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("FROM characters"), "the response must not carry SQL: " + body);
        assertFalse(body.contains("IllegalStateException"), "the response must not carry exception text: " + body);
    }

    @Test
    void unknownPathUnderTheRouteIsANotFound() throws Exception {
        StubCharacterSelectionService service = new StubCharacterSelectionService(List.of());

        mockMvcFor(service).perform(get("/api/crafting/selector-options/does-not-exist"))
                .andExpect(status().isNotFound());

        assertEquals(0, service.craftingCallCount);
    }

    // ---------- stub ----------

    /**
     * Stands in for the real application service. Subclassing (rather than an interface) keeps the
     * production controller depending on the same concrete type the JavaFX views use, which is what
     * the story's "keep JavaFX on the same implementation" constraint asks for.
     */
    private static final class StubCharacterSelectionService extends CharacterSelectionService {
        private final List<CharacterRepository.DiscRow> rows;
        private final SQLException sqlFailure;
        private final RuntimeException runtimeFailure;
        int craftingCallCount;
        int namesCallCount;

        StubCharacterSelectionService(List<CharacterRepository.DiscRow> rows) {
            this(rows, null, null);
        }

        StubCharacterSelectionService(SQLException sqlFailure) {
            this(List.of(), sqlFailure, null);
        }

        StubCharacterSelectionService(RuntimeException runtimeFailure) {
            this(List.of(), null, runtimeFailure);
        }

        private StubCharacterSelectionService(List<CharacterRepository.DiscRow> rows,
                                              SQLException sqlFailure,
                                              RuntimeException runtimeFailure) {
            this.rows = rows;
            this.sqlFailure = sqlFailure;
            this.runtimeFailure = runtimeFailure;
        }

        @Override
        public List<CharacterRepository.DiscRow> getCraftingCharacterOptions() throws SQLException {
            craftingCallCount++;
            if (sqlFailure != null) throw sqlFailure;
            if (runtimeFailure != null) throw runtimeFailure;
            return rows;
        }

        @Override
        public List<String> getCharacterNames() {
            namesCallCount++;
            return List.of();
        }
    }
}
