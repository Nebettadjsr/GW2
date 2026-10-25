package web;

import application.access.AccountContext;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Standalone-MockMvc default request carrying the context {@code AccountAccessInterceptor} would
 * have published, so a controller test exercises the scoped path without the guard (the guard has
 * its own contract tests).
 */
final class AuthorizedRequests {

    static final UUID ACCOUNT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private AuthorizedRequests() {}

    static MockHttpServletRequestBuilder defaultRequest() {
        return forAccount(ACCOUNT_ID);
    }

    static MockHttpServletRequestBuilder forAccount(UUID accountId) {
        return MockMvcRequestBuilders.get("/").requestAttr(web.access.RequestAccountContext.ATTRIBUTE,
                new AccountContext(accountId.toString(), Set.of(), Set.of(), "ctx-1",
                        Instant.parse("2099-01-01T00:00:00Z")));
    }
}
