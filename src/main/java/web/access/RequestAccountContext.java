package web.access;

import application.access.AccountContext;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Where the authorized {@link AccountContext} of the request being served lives (STORY-API-010,
 * TARGET_ARCHITECTURE.md §8.1 "Request and application scope").
 *
 * <p>A request attribute rather than a thread-local or a process-wide "current account": the scope
 * must not be inherited implicitly by a worker, and an admitted operation has to be handed the
 * context explicitly so it keeps that scope after its session ends. Only
 * {@link AccountAccessInterceptor} publishes one, and only for a request it authenticated.
 */
public final class RequestAccountContext {

    private static final String ATTRIBUTE = RequestAccountContext.class.getName();

    private RequestAccountContext() {}

    static void publish(HttpServletRequest request, AccountContext context) {
        request.setAttribute(ATTRIBUTE, context);
    }

    /** Empty whenever the request was not authorized, so a caller cannot mistake absence for scope. */
    public static Optional<AccountContext> of(HttpServletRequest request) {
        Object context = request.getAttribute(ATTRIBUTE);
        return context instanceof AccountContext scope ? Optional.of(scope) : Optional.empty();
    }

    /** For a route that must not run unscoped; the guard has already answered the caller if absent. */
    public static AccountContext required(HttpServletRequest request) {
        return of(request).orElseThrow(() ->
                new IllegalStateException("account work requires an authorized account context"));
    }
}
