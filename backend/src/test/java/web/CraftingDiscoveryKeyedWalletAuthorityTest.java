package web;

import application.AccountRefreshService;
import application.CraftingDiscoveryService;
import application.ScopedAccountRefreshCoordinator;
import application.access.AccountAccessService;
import domain.access.AccountGrant;
import domain.access.AccountProof;
import application.access.AccountSessions;
import domain.access.InvalidCredentialException;
import craft.CraftingSettings;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.DiscChoice;
import web.task.BackgroundTaskService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Keyed Discovery wallet authority (issue #372) through the real gate and the real {@link AccountAccessService}
 * proof: a wide session never lends its wallet authority to a presented key whose own proven scope lacks the
 * wallet grant or endpoint, while a keyless read keeps the validated session authority. Table and detail agree.
 */
class CraftingDiscoveryKeyedWalletAuthorityTest {

    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final Set<AccountGrant> BASE = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES,
            AccountGrant.CHARACTERS, AccountGrant.UNLOCKS);
    private static final String BODY = "{\"scope\": {\"discipline\": \"Chef\", \"characterName\": \"Aria\", \"rating\": 400}}";
    private static final String DETAIL = "{\"recipeId\": 1, \"calculation\": " + BODY + "}";

    private final BackgroundTaskService tasks = new BackgroundTaskService();
    private final AccountSessions sessions = new AccountSessions(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), 8);
    private final List<Boolean> bound = new ArrayList<>();

    private static Set<AccountGrant> withWallet() {
        Set<AccountGrant> g = new HashSet<>(BASE);
        g.add(AccountGrant.WALLET);
        return g;
    }

    private AccountSessions.Issued wideSession() {
        return sessions.establish(new AccountProof(A, withWallet(), Set.of(), null));
    }

    private MockMvc mvc() {
        var coordinator = new ScopedAccountRefreshCoordinator(
                new AccountRefreshService(new sync.AccountRefreshGateway() { }),
                new BackgroundTaskServiceTasks(tasks),
                new AccountAccessService(key -> switch (key) {
                    case "wallet-key" -> new AccountProof(A, withWallet(), Set.of(), null);
                    case "no-grant-key" -> new AccountProof(A, BASE, Set.of(), null);
                    case "no-endpoint-key" -> new AccountProof(A, withWallet(), Set.of("/v2/account/bank", "/v2/account/materials", "/v2/account/recipes", "/v2/characters"), null);
                    default -> throw new InvalidCredentialException("rejected");
                }, sessions),
                key -> url -> null);
        return MockMvcBuilders.standaloneSetup(new CraftingDiscoveryApiController(Stub::new, null, coordinator, null))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private final class Stub extends CraftingDiscoveryService {
        Stub() { super(null, null, null, null, null); }

        @Override public CraftingDiscoveryService forScope(model.AccountScope scope, boolean walletAuthorized) {
            bound.add(walletAuthorized);
            return this;
        }
        @Override public InputState inputState() { return new InputState(Set.of(), Set.of()); }
        @Override public void verifySelectedCharacter(DiscChoice choice) { }
        @Override public void recordAuthorizedUse() { }
        @Override public DiscoveryData reload(DiscChoice choice, CraftingSettings settings) {
            return new DiscoveryData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        }
        @Override public application.CraftingResolutionDetail resolveDetail(int id, DiscChoice c, CraftingSettings s) {
            return null;
        }
    }

    @AfterEach
    void close() {
        tasks.close();
    }

    private void call(String path, String body, AccountSessions.Issued session, String key) throws Exception {
        MockHttpServletRequestBuilder b = post(path).contentType(MediaType.APPLICATION_JSON)
                .requestAttr(web.access.RequestAccountContext.ATTRIBUTE, session.context())
                .cookie(new Cookie(web.access.AccountAccessHttp.SESSION_COOKIE, session.sessionId()));
        if (key != null) b.header(web.access.AccountAccessHttp.API_KEY_HEADER, key);
        mvc().perform(b.content(body));
    }

    private void both(AccountSessions.Issued session, String key) throws Exception {
        call("/api/crafting/discovery", BODY, session, key);
        call("/api/crafting/discovery/resolution", DETAIL, session, key);
    }

    @Test
    void keylessReadKeepsTheValidatedSessionWalletAuthority() throws Exception {
        both(wideSession(), null);
        assertEquals(List.of(true, true), bound);
    }

    @Test
    void keyWithWalletGrantAndEndpointOnAWideSessionIsAuthorized() throws Exception {
        both(wideSession(), "wallet-key");
        assertEquals(List.of(true, true), bound);
    }

    @Test
    void sameAccountKeyWithoutWalletGrantCannotBorrowTheWideSessionWallet() throws Exception {
        both(wideSession(), "no-grant-key");
        assertEquals(List.of(false, false), bound);
    }

    @Test
    void sameAccountKeyWithoutWalletEndpointCannotBorrowTheWideSessionWallet() throws Exception {
        both(wideSession(), "no-endpoint-key");
        assertEquals(List.of(false, false), bound);
    }

    @Test
    void narrowSessionNeverGainsWalletAuthorityFromAWalletKey() throws Exception {
        both(sessions.establish(new AccountProof(A, BASE, Set.of(), null)), "wallet-key");
        assertEquals(List.of(false, false), bound);
    }
}
