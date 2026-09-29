package web;

import application.AccountLuckService;
import application.CurrentAccountLuckService;
import application.CurrentAccountLuckService.AccountIdentityUnavailableException;
import application.CurrentAccountLuckService.AccountLuckNotSyncedException;
import application.CurrentAccountLuckService.AccountLuckSyncUnavailableException;
import luck.MagicFindProgression;
import model.AccountLuck;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.SQLException;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountLuckApiControllerTest {
    @Test
    void returnsResolvedCurrentAccountWithoutAcceptingAnAccountId() throws Exception {
        var status = new AccountLuckService(null, MagicFindProgression.canonical()).resolve(
                new AccountLuck("account-a", 14_500, Instant.parse("2026-09-29T00:00:00Z")));
        var service = new CurrentAccountLuckService(null, null) {
            @Override public AccountLuckService.AccountLuckStatus currentStatus() { return status; }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new AccountLuckApiController(service))
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
        var service = new CurrentAccountLuckService(null, null) {
            @Override public AccountLuckService.AccountLuckStatus currentStatus() { return status; }
        };
        MockMvcBuilders.standaloneSetup(new AccountLuckApiController(service))
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
    void mapsMissingSyncAndIdentityFailure() throws Exception {
        var missing = new CurrentAccountLuckService(null, null) {
            @Override public AccountLuckService.AccountLuckStatus currentStatus() {
                throw new AccountLuckNotSyncedException();
            }
        };
        MockMvcBuilders.standaloneSetup(new AccountLuckApiController(missing))
                .setControllerAdvice(new AccountLuckApiExceptionHandler()).build()
                .perform(get("/api/account/luck"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LUCK_NOT_SYNCED"));

        var identityFailure = new CurrentAccountLuckService(null, null) {
            @Override public AccountLuckService.AccountLuckStatus currentStatus() throws SQLException, AccountIdentityUnavailableException {
                throw new AccountIdentityUnavailableException(new IllegalStateException());
            }
        };
        MockMvcBuilders.standaloneSetup(new AccountLuckApiController(identityFailure))
                .setControllerAdvice(new AccountLuckApiExceptionHandler()).build()
                .perform(get("/api/account/luck"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("ACCOUNT_IDENTITY_UNAVAILABLE"));

        var syncFailure = new CurrentAccountLuckService(null, null) {
            @Override public AccountLuckService.AccountLuckStatus currentStatus() throws AccountLuckSyncUnavailableException {
                throw new AccountLuckSyncUnavailableException(new IllegalStateException());
            }
        };
        MockMvcBuilders.standaloneSetup(new AccountLuckApiController(syncFailure))
                .setControllerAdvice(new AccountLuckApiExceptionHandler()).build()
                .perform(get("/api/account/luck"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LUCK_SYNC_UNAVAILABLE"));
    }
}
