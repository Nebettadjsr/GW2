package application;

import model.AccountScope;
import org.junit.jupiter.api.Test;
import repo.AccountSourceStateRepository.SourceState;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STORY-API-011: keyless reads classify the source marker; a missing source is never an empty result. */
class ScopedSourceGateTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final AccountScope SCOPE = AccountScope.of(UUID.randomUUID());

    private final List<Instant> activity = new ArrayList<>();

    private ScopedSourceGate gate(ScopedSourceGate.StateReader states) {
        return new ScopedSourceGate(states, (scope, at) -> activity.add(at),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
    }

    @Test
    void neverPublishedSourceIsPendingWithNoDataAndNeverLoadsRows() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        var read = gate((scope, source) -> Optional.empty()).read(SCOPE, "BANK", () -> {
            loads.incrementAndGet();
            return List.of();
        });
        assertEquals(ScopedSourceGate.Status.PENDING, read.status());
        assertFalse(read.hasData());
        assertNull(read.refresh());
        assertEquals(0, loads.get(), "pending must not fabricate an empty result from rows");
        assertEquals(1, activity.size(), "an authorized pending read is still authorized use");
    }

    @Test
    void publishedEmptySourceIsUsableAndCurrent() throws Exception {
        var read = gate((scope, source) -> Optional.of(new SourceState(source, 1, NOW.minusSeconds(60))))
                .read(SCOPE, "BANK", List::<String>of);
        assertTrue(read.hasData());
        assertEquals(ScopedSourceGate.Status.CURRENT, read.status());
        assertNull(read.refresh());
    }

    @Test
    void expiredSourceStaysUsableAndReportsCredentialRequired() throws Exception {
        var read = gate((scope, source) -> Optional.of(new SourceState(source, 4, NOW.minus(Duration.ofHours(3)))))
                .read(SCOPE, "MATERIALS", () -> List.of("row"));
        assertEquals(List.of("row"), read.data());
        assertEquals(ScopedSourceGate.Status.STALE, read.status());
        assertEquals(ScopedSourceGate.CREDENTIAL_REQUIRED, read.refresh());
    }

    @Test
    void aGenerationThatMovesDuringTheReadIsRetriedSoMarkerAndRowsAgree() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        // before#1=gen1, after#1=gen2 (moved) -> retry: before#2=gen2, after#2=gen2
        long[] generations = {1, 2, 2, 2};
        var read = gate((scope, source) -> Optional.of(
                new SourceState(source, generations[reads.getAndIncrement()], NOW))).read(SCOPE, "LUCK",
                () -> "rows-" + loads.incrementAndGet());
        assertEquals("rows-2", read.data());
        assertEquals(2, loads.get());
    }

    @Test
    void aFailedReadRecordsNoActivity() {
        assertThrows(SQLException.class, () -> gate((scope, source) -> Optional.of(new SourceState(source, 1, NOW)))
                .read(SCOPE, "BANK", () -> { throw new SQLException("down"); }));
        assertTrue(activity.isEmpty());
    }
}
