package application.ops;

import application.access.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OwnerOperationsServiceTest {
    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC);
    private final AccountSessions sessions = new AccountSessions(clock, 10);
    private final AccountAccessService access = new AccountAccessService(key -> new AccountProof(
            key.equals("owner") ? OWNER : OTHER, Set.of(AccountGrant.ACCOUNT), Set.of(), null), sessions);
    private final AtomicInteger touched = new AtomicInteger();
    private final HistoryStore store = new HistoryStore() {
        public void appendMinute(MinuteBucket b) { }
        public void appendHour(HourBucket b) { }
        public Snapshot snapshot() { touched.incrementAndGet(); return new Snapshot(true, false, List.of(), List.of()); }
    };
    private final OperationalHistoryService history = new OperationalHistoryService(clock,
            new ForegroundActivityTracker(clock), store);
    private final DatabaseSizeService size = new DatabaseSizeService(() -> { touched.incrementAndGet(); return 9; }, clock);
    private final OwnerOperationsService service = new OwnerOperationsService(access, new OwnerPolicy(OWNER),
            history, size, clock);

    @Test
    void denialsPrecedeEveryCollaboratorAndCarryNoPayload() throws Exception {
        touched.set(0); // construction reads retained history once; only post-construction access matters
        var ordinary = access.establish("other");
        var summaryNone = service.summary(null, null);
        var summaryUnknown = service.summary("nope", null);
        var summaryOrdinary = service.summary(ordinary.sessionId(), ordinary.context().accessContext());
        var mismatch = service.export(ordinary.sessionId(), "wrong-context");
        assertEquals(OwnerOperationsService.Denial.UNAUTHENTICATED, summaryNone.denial());
        assertEquals(OwnerOperationsService.Denial.UNAUTHENTICATED, summaryUnknown.denial());
        assertEquals(OwnerOperationsService.Denial.FORBIDDEN, summaryOrdinary.denial());
        assertEquals(OwnerOperationsService.Denial.FORBIDDEN, mismatch.denial());
        for (var r : List.of(summaryNone, summaryUnknown, summaryOrdinary)) {
            assertNull(r.value());
            assertEquals("no-store", r.cacheControl());
        }
        assertNull(mismatch.value());
        assertEquals(0, touched.get(), "no history/database collaborator may run before authorization");
        assertEquals(0, new OwnerOperationsService(access, null, history, size, clock)
                .summary(ordinary.sessionId(), ordinary.context().accessContext()).value() == null ? 0 : 1);
    }

    @Test
    void ownerGetsBoundedSummaryAndExport() throws Exception {
        var owner = access.establish("owner");
        var summary = service.summary(owner.sessionId(), owner.context().accessContext());
        assertTrue(summary.authorized());
        assertEquals(DatabaseSize.State.AVAILABLE, summary.value().databaseSize().state());
        assertEquals(9L, summary.value().databaseSize().bytes());
        assertTrue(summary.value().historyAvailable());
        var export = service.export(owner.sessionId(), owner.context().accessContext());
        assertTrue(export.authorized());
        assertEquals("no-store", export.cacheControl());
        assertTrue(export.value().minutes().isEmpty());
    }
}
