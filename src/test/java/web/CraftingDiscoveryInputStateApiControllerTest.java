package web;

import application.AccountRefreshService;
import application.CraftingDiscoveryService;
import application.ScopedAccountRefreshCoordinator;
import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountSessions;
import application.access.InvalidCredentialException;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.AccountSyncStateRepository;
import repo.DiscChoice;
import craft.CraftingSettings;
import sync.AccountRefreshGateway;
import sync.AccountSync;
import web.task.BackgroundTaskService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** STORY-API-011: Discovery's missing / stale account inputs and keyed recovery, offline. */
class CraftingDiscoveryInputStateApiControllerTest {

    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final Set<AccountGrant> GRANTS = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES,
            AccountGrant.CHARACTERS, AccountGrant.UNLOCKS);
    private static final String BODY =
            "{\"scope\": {\"discipline\": \"Chef\", \"characterName\": \"Aria\", \"rating\": 400}}";

    private final BackgroundTaskService tasks = new BackgroundTaskService();
    private final AccountSessions sessions = new AccountSessions(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), 8);
    private final List<String> fetchedWith = new CopyOnWriteArrayList<>();
    private final AccountProof proof = new AccountProof(A, GRANTS, Set.of(), null);
    private final AccountSessions.Issued session = sessions.establish(proof);
    private final ScopedAccountRefreshCoordinator coordinator = new ScopedAccountRefreshCoordinator(
            new AccountRefreshService(new AccountRefreshGateway() {
                @Override public void syncAccountBank(model.AccountScope s, AccountSync.AuthFetcher f) throws Exception {
                    fetchedWith.add("BANK:" + f.get("https://offline").asText());
                }
                @Override public void syncAccountMaterials(model.AccountScope s, AccountSync.AuthFetcher f) throws Exception {
                    fetchedWith.add("MATERIALS:" + f.get("https://offline").asText());
                }
                @Override public void syncAccountRecipes(model.AccountScope s, AccountSync.AuthFetcher f) throws Exception {
                    fetchedWith.add("RECIPES:" + f.get("https://offline").asText());
                }
                @Override public void syncCharacterCraftingAndRecipes(model.AccountScope s, AccountSync.AuthFetcher f)
                        throws Exception {
                    fetchedWith.add("CHARACTERS:" + f.get("https://offline").asText());
                }
            }), new BackgroundTaskServiceTasks(tasks),
            new AccountAccessService(key -> {
                if (!key.equals("good-key")) throw new InvalidCredentialException("rejected");
                return proof;
            }, sessions),
            key -> url -> TextNode.valueOf(key));

    private final Stub stub = new Stub();
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new CraftingDiscoveryApiController(() -> stub, null, coordinator, null))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @AfterEach
    void close() {
        tasks.close();
    }

    private MockHttpServletRequestBuilder request(String path, boolean withKey) {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON)
                .requestAttr(web.access.RequestAccountContext.ATTRIBUTE, session.context())
                .cookie(new Cookie(web.access.AccountAccessHttp.SESSION_COOKIE, session.sessionId()));
        if (withKey) builder.header(web.access.AccountAccessHttp.API_KEY_HEADER, "good-key");
        return builder;
    }

    @Test
    void missingInputsArePendingWithoutACalculationOrAnAdmission() throws Exception {
        stub.state = new CraftingDiscoveryService.InputState(Set.of("BANK", "CHARACTERS"), Set.of());

        mvc.perform(request("/api/crafting/discovery", false).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.freshness.status").value("PENDING"))
                .andExpect(jsonPath("$.freshness.sources[0]").value("BANK"))
                .andExpect(jsonPath("$.freshness.refresh").value("CREDENTIAL_REQUIRED"))
                .andExpect(jsonPath("$.freshness.taskId").doesNotExist());

        assertEquals(0, stub.reloads, "no calculation from missing inputs");
        assertEquals(1, stub.activity, "authorized use is recorded, never freshness");
        assertTrue(fetchedWith.isEmpty(), "no refresh admitted without a key");
        assertTrue(coordinator.outstandingSources(model.AccountScope.of(java.util.UUID.fromString(A))).isEmpty());

        mvc.perform(request("/api/crafting/discovery/resolution", false)
                        .content("{\"recipeId\": 7, \"calculation\": " + BODY + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.freshness.status").value("PENDING"))
                .andExpect(jsonPath("$.row").doesNotExist());
        assertEquals(0, stub.details);
    }

    @Test
    void staleInputsStayUsableAndReportCredentialRequired() throws Exception {
        stub.state = new CraftingDiscoveryService.InputState(Set.of(), Set.of("BANK"));

        mvc.perform(request("/api/crafting/discovery", false).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness.status").value("STALE"))
                .andExpect(jsonPath("$.freshness.sources[0]").value("BANK"))
                .andExpect(jsonPath("$.freshness.refresh").value("CREDENTIAL_REQUIRED"));

        assertEquals(1, stub.reloads);
        assertTrue(fetchedWith.isEmpty(), "a keyless read admits nothing and uses no configured credential");
    }

    @Test
    void currentInputsKeepTheOriginalResponseShape() throws Exception {
        mvc.perform(request("/api/crafting/discovery", false).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness").doesNotExist())
                .andExpect(jsonPath("$.rowCount").value(0));
    }

    @Test
    void aSuppliedKeyAdmitsTheScopedRefreshForTheNeededSources() throws Exception {
        stub.state = new CraftingDiscoveryService.InputState(Set.of(), Set.of("BANK", "MATERIALS"));

        String taskId = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(request("/api/crafting/discovery", true).content(BODY))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.freshness.status").value("STALE"))
                        .andExpect(jsonPath("$.freshness.refresh").value("ADMITTED"))
                        .andReturn().getResponse().getContentAsString(), "$.freshness.taskId");

        for (int i = 0; i < 500 && !tasks.find(taskId).orElseThrow().state().isTerminal(); i++) Thread.sleep(10);
        assertEquals(List.of("BANK:good-key", "MATERIALS:good-key"),
                fetchedWith.stream().sorted().toList());
    }

    @Test
    void anInvalidKeyIsRejectedBeforeAnyWork() throws Exception {
        stub.state = new CraftingDiscoveryService.InputState(Set.of("BANK"), Set.of());
        mvc.perform(request("/api/crafting/discovery", false)
                        .header(web.access.AccountAccessHttp.API_KEY_HEADER, "bad-key").content(BODY))
                .andExpect(status().isUnauthorized());
        assertEquals(0, stub.reloads);
        assertEquals(0, stub.activity);
        assertTrue(fetchedWith.isEmpty());
    }

    private static final class Stub extends CraftingDiscoveryService {
        InputState state = new InputState(Set.of(), Set.of());
        int reloads;
        int details;
        int activity;

        Stub() {
            super(null, null, null, null, null, null);
        }

        @Override public CraftingDiscoveryService forScope(model.AccountScope scope) { return this; }
        @Override public InputState inputState() { return state; }
        @Override public void verifySelectedCharacter(DiscChoice choice) { }
        @Override public void recordAuthorizedUse() { activity++; }

        @Override
        public DiscoveryData reload(DiscChoice choice, CraftingSettings settings) throws SQLException {
            reloads++;
            return new DiscoveryData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        }

        @Override
        public application.CraftingResolutionDetail resolveDetail(int recipeId, DiscChoice choice,
                                                                  CraftingSettings settings) {
            details++;
            return null;
        }
    }
}
