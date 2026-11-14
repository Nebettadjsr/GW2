package web;

import application.access.AccountContext;
import application.ops.*;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import web.access.RequestAccountContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OperationalObservationFilterTest {
    private final Clock base = Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC);
    private final OpsTestClock clock = new OpsTestClock();
    private final ForegroundActivityTracker tracker = new ForegroundActivityTracker(clock, clock::nanos, 10);
    private final java.util.List<MinuteBucket> written = new java.util.ArrayList<>();
    private final HistoryStore store = new HistoryStore() {
        public void appendMinute(MinuteBucket b) { written.add(b); }
        public void appendHour(HourBucket b) { }
        public Snapshot snapshot() { return new Snapshot(true, false, List.copyOf(written), List.of()); }
    };
    private final OperationalHistoryService service = new OperationalHistoryService(clock, tracker, store);
    private final OperationalObservationFilter filter = new OperationalObservationFilter(service);

    /** Wall and monotonic clocks advanced together. */
    static final class OpsTestClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T10:00:00Z");
        private long nanos;
        void advanceMinutes(long m) { now = now.plusSeconds(60 * m); nanos += m * 60_000_000_000L; }
        long nanos() { return nanos; }
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    private MockHttpServletResponse run(String method, String uri, String query, boolean authenticated, int status)
            throws Exception {
        var request = new MockHttpServletRequest(method, uri);
        request.setQueryString(query);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                if (authenticated) request.setAttribute(RequestAccountContext.ATTRIBUTE, new AccountContext(
                        UUID.randomUUID().toString(), Set.of(), Set.of(), "ctx", Instant.MAX));
                ((HttpServletResponse) rs).setStatus(status);
            }
        });
        return response;
    }

    @Test
    void authenticatedForegroundRequestCountsOnceAndStoresNoQueryOrIdentity() throws Exception {
        clock.advanceMinutes(6);
        run("GET", "/api/account/characters/Bob%20X/inventory", "accountId=secret&key=abc", true, 200);
        assertEquals(1, tracker.snapshot().exactCount());
        clock.advanceMinutes(1);
        service.flush();
        String json = new tools.jackson.databind.json.JsonMapper().writeValueAsString(written);
        assertTrue(json.contains("INVENTORY"));
        assertFalse(json.contains("secret") || json.contains("Bob"));
    }

    @Test
    void statusPollingAndSessionRoutesAreNotCountedEvenWhenAuthenticated() throws Exception {
        clock.advanceMinutes(6);
        run("GET", "/api/system/status", null, true, 200);
        run("GET", "/api/system/status/details", null, true, 200);
        run("GET", "/api/sync/tasks/abc", null, true, 200);
        run("POST", "/api/session/renew", null, true, 200);
        run("POST", "/api/session/logout", null, true, 200);
        assertEquals(0, tracker.snapshot().exactCount());
    }

    @Test
    void rejectedUnauthenticatedRequestIsMeasuredButNotCountedAsAPlayer() throws Exception {
        clock.advanceMinutes(6);
        run("GET", "/api/account/bank", null, false, 401);
        assertEquals(0, tracker.snapshot().exactCount());
        clock.advanceMinutes(1);
        service.flush();
        assertEquals(1, written.stream().filter(b -> !b.routeGroups().isEmpty()).findFirst().orElseThrow().routeGroups().get(0).clientError());
    }

    @Test
    void thrownExceptionIsCountedAsFailedResponseAndStillPropagates() {
        var request = new MockHttpServletRequest("GET", "/api/items/1");
        assertThrows(java.io.IOException.class, () -> filter.doFilter(request, new MockHttpServletResponse(),
                (rq, rs) -> { throw new java.io.IOException("boom"); }));
        clock.advanceMinutes(1);
        service.flush();
        assertEquals(1, written.stream().filter(b -> !b.routeGroups().isEmpty()).findFirst().orElseThrow().routeGroups().get(0).failed());
    }

    @Test
    void routePathStripsContextMatrixParametersAndRejectsBadEncoding() {
        var request = new MockHttpServletRequest("GET", "/ctx/api/account;jsessionid=abc/bank");
        request.setContextPath("/ctx");
        assertEquals("/api/account/bank", OperationalObservationFilter.routePath(request));
        assertEquals("/api/undecodable",
                OperationalObservationFilter.routePath(new MockHttpServletRequest("GET", "/api/%zz")));
    }
}
