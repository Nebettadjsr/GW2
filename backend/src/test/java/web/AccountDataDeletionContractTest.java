package web;

import application.AccountDataDeletionService;
import application.access.AccountAccessService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** STORY-DELETE-001 HTTP mapping; the deletion service is faked so no database or upstream is used. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"gw2.account-luck.schema-init=false", "gw2.account-access.test-fixtures=true"})
@AutoConfigureMockMvc
class AccountDataDeletionContractTest {
    private static final String ORIGIN = "http://localhost";
    private static final String ACCOUNT_B = "D29467C6-F5AD-E211-8756-78E7D1936222";

    @Autowired MockMvc http;
    @Autowired AccountAccessService access;
    @MockitoBean AccountDataDeletionService deletion;

    private record Proof(Cookie cookie, String context, String accountId) {}

    private Proof prove(String key) throws Exception {
        var response = http.perform(post("/api/session").header("Origin", ORIGIN).header("X-GW2-API-Key", key))
                .andExpect(status().isOk()).andReturn().getResponse();
        String setCookie = response.getHeader("Set-Cookie");
        String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        return new Proof(new Cookie("GW2_SESSION", value),
                com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.accessContext"),
                com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.accountId"));
    }

    @Test
    void deletionMapsEveryOutcomeAsNoStoreWithFixedCodes() throws Exception {
        var a = prove("fixture-key-a");
        when(deletion.delete(any(), anyInt())).thenReturn(AccountDataDeletionService.Result.DELETED);
        http.perform(delete("/api/account/data").header("Origin", ORIGIN).cookie(a.cookie())
                        .header("X-Account-Context", a.context()))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", containsString("no-store")));

        when(deletion.delete(any(), anyInt())).thenReturn(AccountDataDeletionService.Result.WORK_IN_PROGRESS);
        http.perform(delete("/api/account/data").header("Origin", ORIGIN).cookie(a.cookie())
                        .header("X-Account-Context", a.context()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ACCOUNT_WORK_IN_PROGRESS"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        when(deletion.delete(any(), anyInt())).thenReturn(AccountDataDeletionService.Result.UNAVAILABLE);
        http.perform(delete("/api/account/data").header("Origin", ORIGIN).cookie(a.cookie())
                        .header("X-Account-Context", a.context()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("DATA_STORE_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void sessionAndContextAndOriginAreRequiredBeforeAnyDeletion() throws Exception {
        var a = prove("fixture-key-a");
        var b = prove("fixture-key-b");
        http.perform(delete("/api/account/data").header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("SESSION_REQUIRED"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        http.perform(delete("/api/account/data").header("Origin", ORIGIN).cookie(a.cookie())
                        .header("X-Account-Context", b.context()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ACCESS_CONTEXT_MISMATCH"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        http.perform(delete("/api/account/data").cookie(a.cookie()).header("X-Account-Context", a.context()))
                .andExpect(status().isForbidden());
        verify(deletion, never()).delete(any(), anyInt());
    }

    @Test
    void callerSuppliedAccountIdentityCannotRedirectTheTarget() throws Exception {
        var a = prove("fixture-key-a");
        when(deletion.delete(any(), anyInt())).thenReturn(AccountDataDeletionService.Result.DELETED);
        // A query asserting another account is refused at the guard...
        http.perform(delete("/api/account/data").param("accountId", ACCOUNT_B).header("Origin", ORIGIN)
                        .cookie(a.cookie()).header("X-Account-Context", a.context()))
                .andExpect(status().isForbidden());
        verify(deletion, never()).delete(any(), anyInt());
        // ...and a body naming one is ignored: the target is the session's own account.
        http.perform(delete("/api/account/data").header("Origin", ORIGIN).cookie(a.cookie())
                        .header("X-Account-Context", a.context()).contentType("application/json")
                        .content("{\"accountId\":\"" + ACCOUNT_B + "\"}"))
                .andExpect(status().isNoContent());
        var captor = org.mockito.ArgumentCaptor.forClass(model.AccountScope.class);
        verify(deletion).delete(captor.capture(), org.mockito.ArgumentMatchers.eq(0));
        org.junit.jupiter.api.Assertions.assertEquals(a.accountId().toLowerCase(), captor.getValue().id().toLowerCase());
    }

    @Test
    void deletionInvalidatedSessionGetsFixedCodeAndAnExplicitConnectStillWorks() throws Exception {
        var a = prove("fixture-key-a");
        var b = prove("fixture-key-b");
        access.invalidateAccountSessions(a.accountId());
        for (var request : new org.springframework.test.web.servlet.RequestBuilder[] {
                get("/api/account/luck").cookie(a.cookie()).header("X-Account-Context", a.context()),
                delete("/api/account/data").header("Origin", ORIGIN).cookie(a.cookie()).header("X-Account-Context", a.context()),
                post("/api/session/renew").header("Origin", ORIGIN).cookie(a.cookie())
                        .header("X-Account-Context", a.context()).header("X-GW2-API-Key", "fixture-key-a")}) {
            http.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("ACCOUNT_DATA_DELETED"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(jsonPath("$.message", not(containsString(a.accountId()))));
        }
        http.perform(get("/api/account/luck").cookie(b.cookie()).header("X-Account-Context", b.context()))
                .andExpect(status().isOk());
        prove("fixture-key-a");
    }
}
