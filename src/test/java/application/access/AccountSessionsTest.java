package application.access;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AccountSessionsTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void readsDoNotExtendAbsoluteLeaseAndSessionExpiresAtFifteenMinutes() {
        var time = new MutableClock(START);
        var sessions = new AccountSessions(time, 4);
        var issued = sessions.establish(proof("account-a", null));

        time.advanceSeconds(14 * 60L);
        assertTrue(sessions.resolve(issued.sessionId(), issued.context().accessContext()).authenticated());
        time.advanceSeconds(60);
        assertEquals(AccountSessions.Outcome.EXPIRED,
                sessions.resolve(issued.sessionId(), issued.context().accessContext()).outcome());
    }

    @Test
    void credentialExpiryCapsLeaseAndAlreadyExpiredCredentialCannotResolve() {
        var time = new MutableClock(START);
        var sessions = new AccountSessions(time, 4);
        var issued = sessions.establish(proof("account-a", START.plusSeconds(30)));

        assertEquals(START.plusSeconds(30), issued.context().expiresAt());
        time.advanceSeconds(30);
        assertEquals(AccountSessions.Outcome.EXPIRED,
                sessions.resolve(issued.sessionId(), issued.context().accessContext()).outcome());
    }

    @Test
    void capacityEvictsOldestProofAndRemainsBounded() {
        var sessions = new AccountSessions(new MutableClock(START), 2);
        var a = sessions.establish(proof("account-a", null));
        var b = sessions.establish(proof("account-b", null));
        var c = sessions.establish(proof("account-c", null));

        assertEquals(2, sessions.size());
        assertEquals(AccountSessions.Outcome.NO_SESSION,
                sessions.resolve(a.sessionId(), a.context().accessContext()).outcome());
        assertTrue(sessions.resolve(b.sessionId(), b.context().accessContext()).authenticated());
        assertTrue(sessions.resolve(c.sessionId(), c.context().accessContext()).authenticated());
    }

    @Test
    void identifiersRotateAndLogoutRevokesOnlySelectedSession() {
        var sessions = new AccountSessions(new MutableClock(START), 4);
        var first = sessions.establish(proof("account-a", null));
        var replacement = sessions.establish(proof("account-a", null));

        assertNotEquals(first.sessionId(), replacement.sessionId());
        assertNotEquals(first.context().accessContext(), replacement.context().accessContext());
        assertTrue(sessions.revoke(first.sessionId()));
        assertFalse(sessions.resolve(first.sessionId(), first.context().accessContext()).authenticated());
        assertTrue(sessions.resolve(replacement.sessionId(), replacement.context().accessContext()).authenticated());
    }

    private static AccountProof proof(String id, Instant expiry) {
        return new AccountProof(id, Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES), Set.of(), expiry);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
