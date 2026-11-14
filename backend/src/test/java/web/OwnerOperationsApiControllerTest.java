package web;

import application.access.AccountAccessService;
import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.AccountSessions;
import application.access.InvalidCredentialException;
import application.access.OwnerPolicy;
import application.ops.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import web.access.AccountAccessHttp;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** STORY-OPS-002 HTTP: authorization before collaborators, caps on top-level buckets, overflow serialization. */
class OwnerOperationsApiControllerTest {
    static final String OWNER = "aaaaaaaa-1111-2222-3333-444444444444";
    static final String OTHER = "bbbbbbbb-1111-2222-3333-444444444444";
    static final Instant NOW = Instant.parse("2026-11-14T12:30:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AccountSessions sessions = new AccountSessions(clock, 8);
    private final AccountAccessService access = new AccountAccessService(
            key -> { throw new InvalidCredentialException("no"); }, sessions);
    private final AtomicInteger touched = new AtomicInteger();
    private final List<MinuteBucket> minutes = new ArrayList<>();
    private final List<HourBucket> hours = new ArrayList<>();
    private final HistoryStore store = new HistoryStore() {
        public void appendMinute(MinuteBucket b) { }
        public void appendHour(HourBucket b) { }
        public Snapshot snapshot() { touched.incrementAndGet(); return new Snapshot(true, false, minutes, hours); }
    };
    private final DatabaseSizeService size = new DatabaseSizeService(() -> { touched.incrementAndGet(); return 0; }, clock);

    private MockMvc mvc(String owner, String link) {
        var history = new OperationalHistoryService(clock, new ForegroundActivityTracker(clock), store);
        var service = new OwnerOperationsService(access, new OwnerPolicy(owner), history, size, clock);
        return MockMvcBuilders.standaloneSetup(new OwnerOperationsApiController(service, null, link)).build();
    }

    private AccountSessions.Issued session(String guid) {
        return sessions.establish(new AccountProof(guid, Set.of(AccountGrant.ACCOUNT), Set.of(), null));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, AccountSessions.Issued s) {
        return b.cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, s.sessionId()))
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, s.context().accessContext());
    }

    private static List<RouteGroupAggregate> allGroups(long overflowCount) {
        List<RouteGroupAggregate> out = new ArrayList<>();
        for (RouteGroup g : RouteGroup.values()) {
            long[] bins = new long[LatencyHistogram.BINS];
            bins[LatencyHistogram.BINS - 1] = overflowCount;
            out.add(new RouteGroupAggregate(g, overflowCount, overflowCount, 0, 0, 0, overflowCount * 6_000_000L,
                    6_000_000L, LatencyHistogram.toBins(bins)));
        }
        return out;
    }

    private static MinuteBucket minute(Instant at) {
        return new MinuteBucket(at, 60, MinuteBucket.Completeness.COMPLETE,
                new ForegroundAccounts(ForegroundAccounts.State.COMPLETE, 2, null), allGroups(1));
    }

    private static final String RANGE = "&from=2026-11-14T06:30:00Z&to=2026-11-14T12:30:00Z";

    @Test
    void everyDenialPrecedesCollaboratorsForSummaryAndExportIncludingInvalidRequests() throws Exception {
        var m = mvc(OWNER, null);
        var noOwner = mvc(null, null); // construction reads retained history once
        touched.set(0);
        var ordinary = session(OTHER);
        m.perform(get("/api/system/operations/summary")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
        m.perform(as(get("/api/system/operations/summary"), ordinary)).andExpect(status().isForbidden());
        m.perform(get("/api/system/operations/export?format=csv&resolution=minute" + RANGE))
                .andExpect(status().isUnauthorized());
        m.perform(as(get("/api/system/operations/export?format=bogus"), ordinary)).andExpect(status().isForbidden());
        m.perform(get("/api/system/operations/export?format=bogus")).andExpect(status().isUnauthorized());
        var owner = session(OWNER);
        m.perform(get("/api/system/operations/summary").cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, owner.sessionId()))
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, "stale")).andExpect(status().isForbidden());
        m.perform(get("/api/system/operations/summary").cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, "expired")))
                .andExpect(status().isUnauthorized());
        noOwner.perform(as(get("/api/system/operations/export?format=csv&resolution=hour" + RANGE), owner))
                .andExpect(status().isForbidden());
        assertEquals(0, touched.get(), "no history or database collaborator before authorization");
    }

    @Test
    void ownerSummaryIsNoStoreFiltersByElapsedWindowAndCarriesOracleLinkState() throws Exception {
        minutes.add(minute(NOW.minusSeconds(7 * 3600).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)));
        minutes.add(minute(Instant.parse("2026-11-14T12:00:00Z")));
        var owner = session(OWNER);
        mvc(OWNER, "https://cloud.oracle.com/monitoring").perform(as(get("/api/system/operations/summary"), owner))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.minutes.length()").value(1))
                .andExpect(jsonPath("$.oracleConsole.configured").value(true))
                .andExpect(jsonPath("$.databaseSize.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.databaseSize.bytes").value(0));
        mvc(OWNER, "http://insecure.example/x?token=1").perform(as(get("/api/system/operations/summary"), owner))
                .andExpect(jsonPath("$.oracleConsole.configured").value(false))
                .andExpect(jsonPath("$.oracleConsole.url").doesNotExist());
    }

    @Test
    void invalidExportRequestsAreRejectedAfterAuthorization() throws Exception {
        var m = mvc(OWNER, null);
        var owner = session(OWNER);
        for (String q : List.of("format=xml&resolution=minute" + RANGE, "format=csv&resolution=day" + RANGE,
                "format=csv&resolution=minute&from=2026-11-14T06:30:00&to=2026-11-14T12:30:00Z",
                "format=csv&resolution=minute&from=nope&to=2026-11-14T12:30:00Z",
                "format=csv&resolution=minute&from=2026-11-14T12:30:00Z&to=2026-11-14T06:30:00Z",
                "format=csv&resolution=minute&from=2026-11-14T00:00:00Z&to=2026-11-14T12:30:00Z", // > 360 min
                "format=json&resolution=hour&from=2026-10-01T00:00:00Z&to=2026-11-14T12:30:00Z", // > 30 days
                "format=json&resolution=hour")) {
            m.perform(as(get("/api/system/operations/export?" + q), owner)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void eightRouteGroupsInOneBucketCountAsOneAndOverflowIsFinite() throws Exception {
        minutes.add(minute(Instant.parse("2026-11-14T12:00:00Z")));
        var owner = session(OWNER);
        var csv = mvc(OWNER, null).perform(as(get("/api/system/operations/export?format=csv&resolution=minute" + RANGE), owner))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String[] lines = csv.split("\n");
        assertEquals(9, lines.length, "header plus eight route rows for one top-level bucket");
        assertTrue(lines[0].startsWith("resolution,bucket_start_utc,observed_seconds,completeness,activity_state"));
        assertTrue(lines[1].endsWith(",5000,true,5000,true"), lines[1]);
        assertFalse(csv.contains("Infinity") || csv.contains("Inf"));
        var json = mvc(OWNER, null).perform(as(get("/api/system/operations/export?format=json&resolution=minute" + RANGE), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.buckets[0].routeGroups.length()").value(8))
                .andExpect(jsonPath("$.buckets[0].routeGroups[0].p95.upperBoundMs").value(5000))
                .andExpect(jsonPath("$.buckets[0].routeGroups[0].p95.overflow").value(true))
                .andExpect(jsonPath("$.buckets[0].routeGroups[0].p95.display").value(">5 s"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(json.contains("Infinity") || json.contains("Inf\""));
    }

    @Test
    void cap360MinuteBucketsAppliesToTopLevelTimestampsAndNothingIsSynthesized() throws Exception {
        Instant start = Instant.parse("2026-11-14T06:30:00Z");
        for (int i = 0; i < 360; i += 2) minutes.add(minute(start.plusSeconds(60L * i))); // gaps stay gaps
        var owner = session(OWNER);
        mvc(OWNER, null).perform(as(get("/api/system/operations/export?format=json&resolution=minute" + RANGE), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.buckets.length()").value(180));
    }

    @Test
    void hourExportAcceptsThirtyDaysAndRejectsMoreThanSevenHundredTwentyHours() throws Exception {
        var m = mvc(OWNER, null);
        var owner = session(OWNER);
        m.perform(as(get("/api/system/operations/export?format=json&resolution=hour&from=2026-10-15T12:30:00Z&to=2026-11-14T12:30:00Z"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.buckets.length()").value(0));
        m.perform(as(get("/api/system/operations/export?format=json&resolution=hour&from=2026-10-14T12:30:00Z&to=2026-11-14T12:30:00Z"), owner))
                .andExpect(status().isBadRequest());
    }
}
