package web;

import application.CraftingDiscoveryService;
import application.access.AccountContext;
import application.access.AccountGrant;
import craft.CraftingSettings;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repo.DiscChoice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Discovery wallet authority through its existing scoped boundary (issue #372): the controller binds the
 * request-local service with wallet authority exactly when the validated context covers the wallet grant and
 * endpoint. No acquisition or valuation scope is involved; an item-only request is unaffected either way.
 */
class CraftingDiscoveryWalletAuthorityTest {

    private static final Set<AccountGrant> BASE = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES,
            AccountGrant.CHARACTERS, AccountGrant.UNLOCKS);
    private static final String BODY = """
            {"scope": {"discipline": "Chef", "characterName": "Aria", "rating": 400},
             "settings": {"useOwnMats": true, "allowBuying": false}}""";

    private final List<Boolean> boundWalletAuthority = new ArrayList<>();
    private int reloads;

    private final class Stub extends CraftingDiscoveryService {
        Stub() { super(null, null, null, null, null); }

        @Override public CraftingDiscoveryService forScope(model.AccountScope scope, boolean walletAuthorized) {
            boundWalletAuthority.add(walletAuthorized);
            return this;
        }
        @Override public InputState inputState() { return new InputState(Set.of(), Set.of()); }
        @Override public void verifySelectedCharacter(DiscChoice choice) { }
        @Override public void recordAuthorizedUse() { }
        @Override public DiscoveryData reload(DiscChoice choice, CraftingSettings settings) {
            reloads++;
            return new DiscoveryData(List.of(), List.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private int perform(AccountContext context) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CraftingDiscoveryApiController(Stub::new))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        return mvc.perform(post("/api/crafting/discovery").contentType(MediaType.APPLICATION_JSON)
                        .requestAttr(web.access.RequestAccountContext.ATTRIBUTE, context).content(BODY))
                .andExpect(status().isOk()).andReturn().getResponse().getStatus();
    }

    private static AccountContext context(Set<AccountGrant> grants, Set<String> endpoints) {
        return new AccountContext(UUID.randomUUID().toString(), grants, endpoints, "ctx",
                Instant.parse("2099-01-01T00:00:00Z"));
    }

    @Test
    void walletAuthorizedContextBindsTheServiceWithWalletAuthority() throws Exception {
        Set<AccountGrant> grants = new java.util.HashSet<>(BASE);
        grants.add(AccountGrant.WALLET);
        perform(context(grants, Set.of()));
        assertEquals(List.of(true), boundWalletAuthority);
        assertEquals(1, reloads);
    }

    @Test
    void missingWalletGrantOrEndpointNeverBindsWalletAuthorityButStillCalculatesItemOnlyRows() throws Exception {
        perform(context(BASE, Set.of()));
        Set<AccountGrant> grants = new java.util.HashSet<>(BASE);
        grants.add(AccountGrant.WALLET);
        perform(context(grants, Set.of("/v2/account/bank")));
        assertEquals(List.of(false, false), boundWalletAuthority);
        assertEquals(2, reloads, "item-only calculation is unaffected by the missing authority");
    }
}
