package web;

import application.ops.ForegroundAccounts;
import application.ops.LatencyHistogram;
import application.ops.MinuteBucket;
import application.ops.OwnerOperationsService;
import application.ops.PeriodDescriptor;
import application.ops.RouteGroupAggregate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import web.access.AccountAccessHttp;
import web.dto.ApiErrorResponse;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owner-only operational summary, period manifest and single-period download (STORY-OPS-002). Authorization is performed by
 * {@link OwnerOperationsService} before any collaborator runs; every answer is {@code no-store}.
 * Deliberately separate from the generic status response.
 */
@RestController
@RequestMapping("/api/system/operations")
public class OwnerOperationsApiController {
    private final OwnerOperationsService operations;
    private final String oracleConsoleUrl;

    public OwnerOperationsApiController(OwnerOperationsService operations,
                                        @org.springframework.beans.factory.annotation.Value("${gw2.ops.oracle-console-url:}")
                                        String oracleConsoleUrl) {
        this.operations = operations;
        this.oracleConsoleUrl = validLink(oracleConsoleUrl);
    }

    /** One non-secret HTTPS link only: no credentials, query or fragment; anything else is "not configured". */
    static String validLink(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null && uri.getRawQuery().toLowerCase().matches(".*(key|token|secret|password|sig).*")
                    || uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()) return null;
            return uri.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record Percentile(long upperBoundMs, boolean overflow, String display) {}

    public record Route(String group, long requests, long http2xx3xx, long http4xx, long http5xx, long failedIncomplete,
                        long meanMs, long maxMs, Percentile p50, Percentile p95) {
        Route withGroup(String name) {
            return new Route(name, requests, http2xx3xx, http4xx, http5xx, failedIncomplete, meanMs, maxMs, p50, p95);
        }
    }

    public record Bucket(Instant startUtc, int observedSeconds, String completeness, ForegroundAccounts foregroundAccounts,
                         Route overall, List<Route> routeGroups) {}

    public record Link(boolean configured, String url) {}

    /**
     * No catalog/account projection here: the UI takes the 60-minute figure from the existing owner details, and
     * reading the catalog summary would query all catalog datasets for one duplicate field.
     */
    public record SummaryBody(Instant generatedAt, ForegroundAccounts foregroundAccounts,
                              application.ops.DatabaseSize databaseSize,
                              boolean historyAvailable,
                              boolean retentionTruncated, List<Bucket> minutes,
                              Bucket latestMinute, Link oracleConsole) {}

    @GetMapping("/summary")
    public ResponseEntity<?> summary(HttpServletRequest request) {
        var result = operations.summary(AccountAccessHttp.presentedSessionId(request).orElse(null),
                AccountAccessHttp.assertedAccessContext(request));
        if (!result.authorized()) return denied(result.denial());
        var s = result.value();
        List<Bucket> minutes = s.minutes().stream().map(OwnerOperationsApiController::minute).toList();
        return ok(MediaType.APPLICATION_JSON, new SummaryBody(s.generatedAt(), s.foregroundAccounts(), s.databaseSize(),
                s.historyAvailable(), s.retentionTruncated(), minutes,
                s.latestMinute() == null ? null : minute(s.latestMinute()),
                new Link(oracleConsoleUrl != null, oracleConsoleUrl)));
    }

    /** Manifest of retained periods; {@code available=false} is an unreadable store, never an idle one. */
    public record PeriodsBody(boolean available, List<PeriodDescriptor> periods) {}

    @GetMapping("/periods")
    public ResponseEntity<?> periods(HttpServletRequest request) {
        var result = operations.periods(AccountAccessHttp.presentedSessionId(request).orElse(null),
                AccountAccessHttp.assertedAccessContext(request));
        if (!result.authorized()) return denied(result.denial());
        return ok(MediaType.APPLICATION_JSON, new PeriodsBody(result.value().available(), result.value().items()));
    }

    /**
     * Streams exactly one server-described CSV. Authorization happens inside the service before the opaque
     * identifier is looked at; an unknown or expired identifier is a 410 and never selects another period.
     */
    @GetMapping("/download")
    public ResponseEntity<?> download(HttpServletRequest request,
                                      @RequestParam(name = "selection", required = false) String selection) {
        var result = operations.download(AccountAccessHttp.presentedSessionId(request).orElse(null),
                AccountAccessHttp.assertedAccessContext(request), selection == null ? "" : selection);
        if (!result.authorized()) return denied(result.denial());
        var download = result.value();
        return switch (download.status()) {
            case OK -> {
                String name = download.descriptor().filename().replaceAll("[^A-Za-z0-9._-]", "_");
                yield ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                        .header("X-Content-Type-Options", "nosniff")
                        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                        .body(new InputStreamResource(download.stream()));
            }
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.GONE).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new ApiErrorResponse("PERIOD_GONE", "That period is no longer available"));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new ApiErrorResponse("HISTORY_UNAVAILABLE", "Activity history is temporarily unavailable"));
        };
    }

    private static Bucket minute(MinuteBucket m) {
        return new Bucket(m.startUtc(), m.observedSeconds(), m.completeness().name(), m.foregroundAccounts(),
                overall(m.routeGroups()), routes(m.routeGroups()));
    }

    /** All route groups merged from raw counts and bins (never by averaging percentiles); null when none. */
    private static Route overall(List<RouteGroupAggregate> groups) {
        if (groups.isEmpty()) return null;
        var merged = RouteGroupAggregate.merge(groups, groups.get(0).group());
        return routes(List.of(merged)).get(0).withGroup("ALL");
    }

    private static List<Route> routes(List<RouteGroupAggregate> groups) {
        List<Route> out = new ArrayList<>();
        for (RouteGroupAggregate a : groups) {
            long[] counts = LatencyHistogram.toCounts(a.histogram());
            out.add(new Route(a.group().name(), a.requests(), a.success(), a.clientError(), a.serverError(), a.failed(),
                    a.requests() == 0 ? 0 : a.elapsedMicrosSum() / a.requests() / 1000, a.elapsedMicrosMax() / 1000,
                    percentile(counts, 0.5), percentile(counts, 0.95)));
        }
        return out;
    }

    private static Percentile percentile(long[] counts, double fraction) {
        Optional<LatencyHistogram.Percentile> p = LatencyHistogram.percentile(counts, fraction);
        return p.map(x -> new Percentile(x.upperBoundMs(), x.overflow(), x.display())).orElse(null);
    }

    private ResponseEntity<?> ok(MediaType type, Object body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(type).body(body);
    }

    private static ResponseEntity<ApiErrorResponse> denied(OwnerOperationsService.Denial denial) {
        boolean unauth = denial == OwnerOperationsService.Denial.UNAUTHENTICATED;
        return ResponseEntity.status(unauth ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse(unauth ? "SESSION_REQUIRED" : "OWNER_REQUIRED",
                        unauth ? "Prove a GW2 API key to continue" : "This operation is restricted to the system owner"));
    }
}
