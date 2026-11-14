package web;

import application.OwnerCatalogSummaryService;
import application.ops.ForegroundAccounts;
import application.ops.HourBucket;
import application.ops.LatencyHistogram;
import application.ops.MinuteBucket;
import application.ops.OwnerOperationsService;
import application.ops.RouteGroupAggregate;
import jakarta.servlet.http.HttpServletRequest;
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
 * Owner-only operational summary and bounded export (STORY-OPS-002). Authorization is performed by
 * {@link OwnerOperationsService} before any collaborator runs; every answer is {@code no-store}.
 * Deliberately separate from the generic status response.
 */
@RestController
@RequestMapping("/api/system/operations")
public class OwnerOperationsApiController {
    private final OwnerOperationsService operations;
    private final OwnerCatalogSummaryService catalogSummary;
    private final String oracleConsoleUrl;

    public OwnerOperationsApiController(OwnerOperationsService operations, OwnerCatalogSummaryService catalogSummary,
                                        @org.springframework.beans.factory.annotation.Value("${gw2.ops.oracle-console-url:}")
                                        String oracleConsoleUrl) {
        this.operations = operations;
        this.catalogSummary = catalogSummary;
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
                         HourBucket.ActivitySummary activityPeak, Integer minuteBuckets, Route overall, List<Route> routeGroups) {}

    public record Link(boolean configured, String url) {}

    public record SummaryBody(Instant generatedAt, ForegroundAccounts foregroundAccounts,
                              application.ops.DatabaseSize databaseSize,
                              OwnerCatalogSummaryService.Accounts accounts, boolean historyAvailable,
                              boolean retentionTruncated, List<Bucket> minutes, List<Bucket> hours,
                              Bucket latestMinute, Link oracleConsole) {}

    @GetMapping("/summary")
    public ResponseEntity<?> summary(HttpServletRequest request) {
        var result = operations.summary(AccountAccessHttp.presentedSessionId(request).orElse(null),
                AccountAccessHttp.assertedAccessContext(request));
        if (!result.authorized()) return denied(result.denial());
        var s = result.value();
        var accounts = catalogSummary == null ? null : catalogSummary.read().accounts();
        List<Bucket> minutes = s.minutes().stream().map(OwnerOperationsApiController::minute).toList();
        List<Bucket> hours = s.hours().stream().map(OwnerOperationsApiController::hour).toList();
        return ok(MediaType.APPLICATION_JSON, new SummaryBody(s.generatedAt(), s.foregroundAccounts(), s.databaseSize(),
                accounts, s.historyAvailable(), s.retentionTruncated(), minutes, hours,
                s.latestMinute() == null ? null : minute(s.latestMinute()),
                new Link(oracleConsoleUrl != null, oracleConsoleUrl)));
    }

    public record ExportBody(Instant generatedAt, String resolution, boolean historyAvailable,
                             boolean retentionTruncated, List<Bucket> buckets) {}

    @GetMapping("/export")
    public ResponseEntity<?> export(HttpServletRequest request,
                                    @RequestParam(name = "format", required = false) String format,
                                    @RequestParam(name = "resolution", required = false) String resolution,
                                    @RequestParam(name = "from", required = false) String from,
                                    @RequestParam(name = "to", required = false) String to) {
        // Parameters are only parsed after the service has authorized; a bad proof never learns validity.
        String session = AccountAccessHttp.presentedSessionId(request).orElse(null);
        String context = AccountAccessHttp.assertedAccessContext(request);
        var probe = operations.authorizeOnly(session, context);
        if (probe != null) return denied(probe);
        boolean csv = "csv".equals(format);
        boolean hourly = "hour".equals(resolution);
        if (!csv && !"json".equals(format) || !hourly && !"minute".equals(resolution)) return invalid("format/resolution");
        Instant start, end;
        try {
            start = strictUtc(from);
            end = strictUtc(to);
        } catch (RuntimeException e) {
            return invalid("from/to");
        }
        OwnerOperationsService.Result<OwnerOperationsService.Export> result;
        try {
            result = operations.export(session, context, hourly, start, end);
        } catch (OwnerOperationsService.InvalidExport e) {
            return invalid("range");
        }
        if (!result.authorized()) return denied(result.denial());
        var e = result.value();
        List<Bucket> buckets = hourly ? e.hours().stream().map(OwnerOperationsApiController::hour).toList()
                : e.minutes().stream().map(OwnerOperationsApiController::minute).toList();
        String name = "attachment; filename=\"activity-" + resolution + "." + (csv ? "csv" : "json") + "\"";
        var body = csv ? (Object) csv(resolution, e, buckets)
                : new ExportBody(e.generatedAt(), resolution, e.historyAvailable(), e.retentionTruncated(), buckets);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, name)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(csv ? MediaType.parseMediaType("text/csv;charset=UTF-8") : MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static Instant strictUtc(String raw) {
        if (raw == null || !raw.endsWith("Z")) throw new IllegalArgumentException("UTC instant required");
        return Instant.parse(raw);
    }

    static final String CSV_HEADER = "resolution,bucket_start_utc,observed_seconds,completeness,activity_state,"
            + "activity_exact_count,activity_lower_bound,route_group,request_count,http_2xx_3xx,http_4xx,http_5xx,"
            + "failed_incomplete,mean_ms,max_ms,p50_upper_bound_ms,p50_overflow,p95_upper_bound_ms,p95_overflow\n";

    private static String csv(String resolution, OwnerOperationsService.Export e, List<Bucket> buckets) {
        StringBuilder sb = new StringBuilder(CSV_HEADER);
        // Export-level facts are explicit comment-free rows only via the JSON form; CSV never invents gap rows.
        for (Bucket b : buckets) {
            String state; String exact = ""; String lower = "";
            if (b.foregroundAccounts() != null) {
                state = b.foregroundAccounts().state().name();
                exact = b.foregroundAccounts().exactCount() == null ? "" : b.foregroundAccounts().exactCount().toString();
                lower = b.foregroundAccounts().lowerBound() == null ? "" : b.foregroundAccounts().lowerBound().toString();
            } else {
                var a = b.activityPeak();
                state = a.anyUnavailable() ? "UNAVAILABLE" : a.anyTruncated() ? "TRUNCATED"
                        : a.anyPartialStartup() ? "PARTIAL_STARTUP" : "COMPLETE";
                // Hourly: peak per-minute estimate, never an hourly distinct total.
                lower = a.maxReportedActive() == null ? "" : a.maxReportedActive().toString();
            }
            String prefix = resolution + "," + b.startUtc() + "," + b.observedSeconds() + "," + b.completeness() + ","
                    + state + "," + exact + "," + lower + ",";
            if (b.routeGroups().isEmpty()) {
                sb.append(prefix).append(",,,,,,,,,,,\n");
            }
            for (Route r : b.routeGroups()) {
                sb.append(prefix).append(r.group()).append(',').append(r.requests()).append(',').append(r.http2xx3xx())
                        .append(',').append(r.http4xx()).append(',').append(r.http5xx()).append(',')
                        .append(r.failedIncomplete()).append(',').append(r.meanMs()).append(',').append(r.maxMs())
                        .append(',').append(pct(r.p50())).append(',').append(pct(r.p95())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String pct(Percentile p) {
        return p == null ? ",": p.upperBoundMs() + "," + p.overflow();
    }

    private static Bucket minute(MinuteBucket m) {
        return new Bucket(m.startUtc(), m.observedSeconds(), m.completeness().name(), m.foregroundAccounts(), null,
                null, overall(m.routeGroups()), routes(m.routeGroups()));
    }

    private static Bucket hour(HourBucket h) {
        return new Bucket(h.startUtc(), h.observedSeconds(), h.completeness().name(), null, h.activity(),
                h.minuteBuckets(), overall(h.routeGroups()), routes(h.routeGroups()));
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

    private static ResponseEntity<ApiErrorResponse> invalid(String what) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse("INVALID_EXPORT_REQUEST", "Invalid export request: " + what));
    }

    private static ResponseEntity<ApiErrorResponse> denied(OwnerOperationsService.Denial denial) {
        boolean unauth = denial == OwnerOperationsService.Denial.UNAUTHENTICATED;
        return ResponseEntity.status(unauth ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApiErrorResponse(unauth ? "SESSION_REQUIRED" : "OWNER_REQUIRED",
                        unauth ? "Prove a GW2 API key to continue" : "This operation is restricted to the system owner"));
    }
}
