package application.access;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AccountAccessServiceKeyedOperationTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void admitsMatchingLiveSessionAndKey() throws Exception {
        var sessions = new AccountSessions(Clock.fixed(NOW, ZoneOffset.UTC), 4);
        var issued = sessions.establish(proof("account-a", Set.of(AccountGrant.ACCOUNT)));
        var service = new AccountAccessService(key -> proof("account-a", Set.of(AccountGrant.ACCOUNT)), sessions);

        assertDoesNotThrow(() -> service.authorizeKeyedOperation(issued.sessionId(), issued.context(), "transient",
                AccountOperation.LUCK_READ));
    }

    @Test
    void rejectsSessionGrantBeforeUpstreamAdmission() {
        var sessions = new AccountSessions(Clock.fixed(NOW, ZoneOffset.UTC), 4);
        var issued = sessions.establish(proof("account-a", Set.of(AccountGrant.ACCOUNT)));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var service = new AccountAccessService(key -> { calls.incrementAndGet(); return proof("account-a", Set.of(AccountGrant.ACCOUNT)); }, sessions);

        assertThrows(AccountAccessService.InsufficientGrantsException.class,
                () -> service.authorizeKeyedOperation(issued.sessionId(), issued.context(), "transient",
                        AccountOperation.BANK_READ));
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsIdentityAndCredentialGrantMismatch() {
        var sessions = new AccountSessions(Clock.fixed(NOW, ZoneOffset.UTC), 4);
        var issued = sessions.establish(proof("account-a", Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES)));
        var wrongIdentity = new AccountAccessService(key -> proof("account-b", Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES)), sessions);
        assertThrows(AccountAccessService.AccountMismatchException.class,
                () -> wrongIdentity.authorizeKeyedOperation(issued.sessionId(), issued.context(), "key", AccountOperation.BANK_READ));

        var narrowCredential = new AccountAccessService(key -> proof("account-a", Set.of(AccountGrant.ACCOUNT)), sessions);
        assertThrows(AccountAccessService.InsufficientGrantsException.class,
                () -> narrowCredential.authorizeKeyedOperation(issued.sessionId(), issued.context(), "key", AccountOperation.BANK_READ));
    }

    @Test
    void rejectsFabricatedOrRevokedSessionContextBeforeProvingKey() throws Exception {
        var sessions = new AccountSessions(Clock.fixed(NOW, ZoneOffset.UTC), 4);
        var issued = sessions.establish(proof("account-a", Set.of(AccountGrant.ACCOUNT)));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var service = new AccountAccessService(key -> {
            calls.incrementAndGet();
            return proof("account-a", Set.of(AccountGrant.ACCOUNT));
        }, sessions);
        var fabricated = new AccountContext("account-a", Set.of(AccountGrant.ACCOUNT), Set.of(),
                "fabricated-context", issued.context().expiresAt());

        assertThrows(IllegalStateException.class, () -> service.authorizeKeyedOperation(
                issued.sessionId(), fabricated, "key", AccountOperation.LUCK_READ));
        sessions.revoke(issued.sessionId());
        assertThrows(IllegalStateException.class, () -> service.authorizeKeyedOperation(
                issued.sessionId(), issued.context(), "key", AccountOperation.LUCK_READ));
        assertEquals(0, calls.get());
    }

    @Test
    void invalidCredentialRevokesSessionButTransientFailureDoesNot() throws Exception {
        var sessions = new AccountSessions(Clock.fixed(NOW, ZoneOffset.UTC), 4);
        var invalidSession = sessions.establish(proof("account-a", Set.of(AccountGrant.ACCOUNT)));
        var invalid = new AccountAccessService(key -> { throw new InvalidCredentialException("invalid"); }, sessions);
        assertThrows(InvalidCredentialException.class, () -> invalid.authorizeKeyedOperation(
                invalidSession.sessionId(), invalidSession.context(), "key", AccountOperation.LUCK_READ));
        assertFalse(sessions.resolve(invalidSession.sessionId(), invalidSession.context().accessContext()).authenticated());

        var transientSession = sessions.establish(proof("account-a", Set.of(AccountGrant.ACCOUNT)));
        var unavailable = new AccountAccessService(key -> { throw new ProofUnavailableException("offline", java.time.Duration.ofSeconds(5)); }, sessions);
        assertThrows(ProofUnavailableException.class, () -> unavailable.authorizeKeyedOperation(
                transientSession.sessionId(), transientSession.context(), "key", AccountOperation.LUCK_READ));
        assertTrue(sessions.resolve(transientSession.sessionId(), transientSession.context().accessContext()).authenticated());
    }

    private static AccountProof proof(String id, Set<AccountGrant> grants) {
        return new AccountProof(id, grants, Set.of(), null);
    }
}
