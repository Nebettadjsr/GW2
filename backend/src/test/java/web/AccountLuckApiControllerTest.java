package web;

import application.AccountLuckService;
import luck.MagicFindProgression;
import model.AccountLuck;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountLuckApiControllerTest {
    @Test
    void returnsResolvedCurrentAccountWithoutAcceptingAnAccountId() throws Exception {
        var status = new AccountLuckService(null, MagicFindProgression.canonical()).resolve(
                new AccountLuck("account-a", 14_500, Instant.parse("2026-09-29T00:00:00Z")));
        var service = new AccountLuckService(null, MagicFindProgression.canonical()) {
            @Override public java.util.Optional<AccountLuckStatus> getStatus(model.AccountScope scope) {
                return java.util.Optional.of(status);
            }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new AccountLuckApiController(service))
                .defaultRequest(AuthorizedRequests.defaultRequest())
                .setControllerAdvice(new AccountLuckApiExceptionHandler()).build();

        mvc.perform(get("/api/account/luck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumedLuck").value(14500))
                .andExpect(jsonPath("$.currentLuckMagicFindPercent").value(50))
                .andExpect(jsonPath("$.cumulativeLuckForCurrentPercent").value(13790))
                .andExpect(jsonPath("$.nextMagicFindPercent").value(51))
                .andExpect(jsonPath("$.cumulativeLuckForNextPercent").value(14550))
                .andExpect(jsonPath("$.luckRemainingToNextPercent").value(50))
                .andExpect(jsonPath("$.targets[0].kind").value("PLUS_5"))
                .andExpect(jsonPath("$.targets[0].magicFindPercent").value(55))
                .andExpect(jsonPath("$.targets[1].kind").value("PLUS_10"))
                .andExpect(jsonPath("$.targets[2].kind").value("CAP"))
                .andExpect(jsonPath("$.targets[2].magicFindPercent").value(300))
                .andExpect(jsonPath("$.fetchedAt").value("2026-09-29T00:00:00Z"));
        mvc.perform(get("/api/account/luck").param("accountId", "someone-else"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void reportsTheCapAsTheCurrentThresholdWithoutInventingANextOne() throws Exception {
        var status = new AccountLuckService(null, MagicFindProgression.canonical()).resolve(
                new AccountLuck("account-a", 4_295_450, Instant.parse("2026-09-29T00:00:00Z")));
        var service = new AccountLuckService(null, MagicFindProgression.canonical()) {
            @Override public java.util.Optional<AccountLuckStatus> getStatus(model.AccountScope scope) {
                return java.util.Optional.of(status);
            }
        };
        MockMvcBuilders.standaloneSetup(new AccountLuckApiController(service))
                .defaultRequest(AuthorizedRequests.defaultRequest())
                .setControllerAdvice(new AccountLuckApiExceptionHandler()).build()
                .perform(get("/api/account/luck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLuckMagicFindPercent").value(300))
                .andExpect(jsonPath("$.cumulativeLuckForCurrentPercent").value(4_295_450))
                .andExpect(jsonPath("$.nextMagicFindPercent").doesNotExist())
                .andExpect(jsonPath("$.cumulativeLuckForNextPercent").doesNotExist())
                .andExpect(jsonPath("$.luckRemainingToNextPercent").value(0));
    }

    @Test
    void reportsCredentialRequiredWhenNoLuckWasPublishedForTheAccount() throws Exception {
        var missing = new AccountLuckService(null, MagicFindProgression.canonical()) {
            @Override public java.util.Optional<AccountLuckStatus> getStatus(model.AccountScope scope) {
                return java.util.Optional.empty();
            }
        };
        MockMvcBuilders.standaloneSetup(new AccountLuckApiController(missing))
                .defaultRequest(AuthorizedRequests.defaultRequest())
                .setControllerAdvice(new AccountLuckApiExceptionHandler()).build()
                .perform(get("/api/account/luck"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CREDENTIAL_REQUIRED"));
    }
}
