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
    private boolean unavailable;
    private boolean truncated;
    private final AtomicInteger touched = new AtomicInteger();
    private final List<MinuteBucket> minutes = new ArrayList<>();
    private final HistoryStore store = new HistoryStore() {
        public void appendMinute(MinuteBucket b) { }
        public Snapshot snapshot() { touched.incrementAndGet(); return new Snapshot(!unavailable, truncated, minutes, List.of()); }
    };
    private final DatabaseSizeService size = new DatabaseSizeService(() -> { touched.incrementAndGet(); return 0; }, clock);

    private final AtomicInteger storeCalls = new AtomicInteger();
    private final List<PeriodDescriptor> descriptors = new ArrayList<>();
    private boolean storeDown;
    private final OperationalPeriodStore periodStore = new OperationalPeriodStore() {
        @Override public List<PeriodDescriptor> listPeriods() {
            storeCalls.incrementAndGet();
            if (storeDown) throw new IllegalStateException("down");
            return List.copyOf(descriptors);
        }

        @Override public Download openDownload(String id) {
            storeCalls.incrementAndGet();
            if (storeDown) return Download.unavailable();
            return descriptors.stream().filter(d -> d.selectionId().equals(id)).findFirst()
                    .map(d -> new Download(Status.OK, d, new java.io.ByteArrayInputStream(
                            ("h\nrow-" + id + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .orElse(Download.notFound());
        }
    };

    private static PeriodDescriptor day(String id, String from, PeriodDescriptor.State state, boolean truncated) {
        Instant f = Instant.parse(from);
        return new PeriodDescriptor(id, PeriodDescriptor.Kind.DAY, f, f.plusSeconds(86400), state,
                truncated ? PeriodDescriptor.Coverage.PARTIAL : PeriodDescriptor.Coverage.FULL, truncated, false, 10,
                "activity-day-" + from.substring(0, 10) + ".csv");
    }

    private MockMvc mvc(String owner, String link) {
        var history = new OperationalHistoryService(clock, new ForegroundActivityTracker(clock), store, periodStore);
        var service = new OwnerOperationsService(access, new OwnerPolicy(owner), history, size, clock);
        return MockMvcBuilders.standaloneSetup(new OwnerOperationsApiController(service, link)).build();
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

    @Test
    void summaryCarriesNoCatalogAccountProjectionAndHasNoCatalogDependency() throws Exception {
        var owner = session(OWNER);
        mvc(OWNER, null).perform(as(get("/api/system/operations/summary"), owner)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accounts").doesNotExist());
        for (var c : OwnerOperationsApiController.class.getConstructors()) {
            for (var type : c.getParameterTypes()) {
                assertEquals(false, type.getName().contains("OwnerCatalogSummaryService"),
                        "the operations read must not be able to query catalog datasets");
            }
        }
    }

    @Test
    void everyDenialPrecedesCollaboratorsForPeriodsAndDownloadIncludingMalformedRequests() throws Exception {
        var m = mvc(OWNER, null);
        var noOwner = mvc(null, null); // construction reads retained history once
        touched.set(0);
        var ordinary = session(OTHER);
        m.perform(get("/api/system/operations/summary")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
        m.perform(as(get("/api/system/operations/summary"), ordinary)).andExpect(status().isForbidden());
        m.perform(get("/api/system/operations/periods")).andExpect(status().isUnauthorized());
        m.perform(get("/api/system/operations/download?selection=x")).andExpect(status().isUnauthorized());
        m.perform(as(get("/api/system/operations/periods"), ordinary)).andExpect(status().isForbidden());
        m.perform(as(get("/api/system/operations/download?selection=x"), ordinary)).andExpect(status().isForbidden());
        m.perform(as(get("/api/system/operations/download"), ordinary)).andExpect(status().isForbidden());
        m.perform(get("/api/system/operations/download")).andExpect(status().isUnauthorized());
        var owner = session(OWNER);
        m.perform(get("/api/system/operations/summary").cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, owner.sessionId()))
                .header(AccountAccessHttp.ACCESS_CONTEXT_HEADER, "stale")).andExpect(status().isForbidden());
        m.perform(get("/api/system/operations/summary").cookie(new Cookie(AccountAccessHttp.SESSION_COOKIE, "expired")))
                .andExpect(status().isUnauthorized());
        noOwner.perform(as(get("/api/system/operations/download?selection=x"), owner)).andExpect(status().isForbidden());
        assertEquals(0, touched.get(), "no history or database collaborator before authorization");
        assertEquals(0, storeCalls.get(), "no period store access or identifier resolution before authorization");
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
    void summaryHasNoHourlyListAndKeepsRecentMinutes() throws Exception {
        minutes.add(minute(Instant.parse("2026-11-14T12:00:00Z")));
        mvc(OWNER, null).perform(as(get("/api/system/operations/summary"), session(OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hours").doesNotExist())
                .andExpect(jsonPath("$.minutes.length()").value(1));
    }

    @Test
    void manifestListsDescriptorsInStoreOrderWithCoverageAndNoStoragePaths() throws Exception {
        descriptors.add(day("d2", "2026-11-14T00:00:00Z", PeriodDescriptor.State.OPEN, true));
        descriptors.add(day("d1", "2026-11-13T00:00:00Z", PeriodDescriptor.State.CLOSED, false));
        var body = mvc(OWNER, null).perform(as(get("/api/system/operations/periods"), session(OWNER)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.periods.length()").value(2))
                .andExpect(jsonPath("$.periods[0].selectionId").value("d2"))
                .andExpect(jsonPath("$.periods[0].state").value("OPEN"))
                .andExpect(jsonPath("$.periods[0].coverage").value("PARTIAL"))
                .andExpect(jsonPath("$.periods[0].truncated").value(true))
                .andExpect(jsonPath("$.periods[1].coverage").value("FULL"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("/app") || body.contains("\\"), body);
    }

    @Test
    void manifestReportsAnUnavailableStoreAsUnavailableNotEmpty() throws Exception {
        storeDown = true;
        mvc(OWNER, null).perform(as(get("/api/system/operations/periods"), session(OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.periods.length()").value(0));
    }

    @Test
    void downloadStreamsExactlyTheSelectedPeriodAsAnAttachment() throws Exception {
        descriptors.add(day("d2", "2026-11-14T00:00:00Z", PeriodDescriptor.State.OPEN, true));
        descriptors.add(day("d1", "2026-11-13T00:00:00Z", PeriodDescriptor.State.CLOSED, false));
        var r = mvc(OWNER, null).perform(as(get("/api/system/operations/download?selection=d1"), session(OWNER)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"activity-day-2026-11-13.csv\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse();
        assertTrue(r.getContentType().startsWith("text/csv"));
        assertEquals("h\nrow-d1\n", r.getContentAsString());
    }

    @Test
    void expiredMissingOrUnavailableSelectionsAreSafeAndNeverFallBack() throws Exception {
        descriptors.add(day("d1", "2026-11-13T00:00:00Z", PeriodDescriptor.State.CLOSED, false));
        var m = mvc(OWNER, null);
        var owner = session(OWNER);
        for (String q : List.of("?selection=evicted", "?selection=", "")) {
            var body = m.perform(as(get("/api/system/operations/download" + q), owner)).andExpect(status().isGone())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.error").value("PERIOD_GONE")).andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("d1") || body.contains("activity-day"), body);
        }
        storeDown = true;
        m.perform(as(get("/api/system/operations/download?selection=d1"), owner))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("HISTORY_UNAVAILABLE"));
    }

    @Test
    void eightRouteGroupsInOneBucketCountAsOneAndOverflowIsFinite() throws Exception {
        minutes.add(minute(Instant.parse("2026-11-14T12:00:00Z")));
        var json = mvc(OWNER, null).perform(as(get("/api/system/operations/summary"), session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minutes.length()").value(1))
                .andExpect(jsonPath("$.minutes[0].routeGroups.length()").value(8))
                .andExpect(jsonPath("$.minutes[0].routeGroups[0].p95.upperBoundMs").value(5000))
                .andExpect(jsonPath("$.minutes[0].routeGroups[0].p95.overflow").value(true))
                .andExpect(jsonPath("$.minutes[0].routeGroups[0].p95.display").value(">5 s"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(json.contains("Infinity") || json.contains("Inf\""));
    }
}
