package web;

import application.ops.OperationalHistoryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import web.access.RequestAccountContext;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The single request-observation point of STORY-OPS-001. Measures backend elapsed time with the monotonic
 * clock and hands only a method, a query-free path, a status and a duration to the in-memory facade. It
 * never reads headers, cookies, bodies or query strings, and a telemetry problem cannot fail the request.
 * The account identity is read only from the context the access guard already established.
 */
public final class OperationalObservationFilter extends OncePerRequestFilter {
    private final OperationalHistoryService history;

    public OperationalObservationFilter(OperationalHistoryService history) {
        this.history = history;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return true; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            observe(request, response, failed, System.nanoTime() - start);
        }
    }

    private void observe(HttpServletRequest request, HttpServletResponse response, boolean failed, long nanos) {
        try {
            Optional<String> account = RequestAccountContext.of(request)
                    .map(context -> context.accountId());
            history.observe(request.getMethod(), routePath(request), account, response.getStatus(), failed, nanos);
        } catch (RuntimeException telemetryFailure) {
            // Never fail a business request because telemetry could not be recorded.
        }
    }

    /** Context-relative, matrix-parameter-free, decoded path; undecodable input collapses to an unknown API path. */
    static String routePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null) return "";
        if (context != null && !context.isEmpty() && uri.startsWith(context)) uri = uri.substring(context.length());
        StringBuilder path = new StringBuilder();
        for (String segment : uri.split("/", -1)) {
            int matrix = segment.indexOf(';');
            String clean = matrix >= 0 ? segment.substring(0, matrix) : segment;
            try {
                clean = URLDecoder.decode(clean.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException undecodable) {
                return "/api/undecodable";
            }
            if (path.length() > 0 || !clean.isEmpty()) path.append('/');
            path.append(clean);
        }
        return path.length() == 0 ? "/" : path.toString();
    }
}
