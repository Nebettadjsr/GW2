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
        var grants = Set.of(AccountGrant.ACCOUNT, AccountGrant.PROGRESSION, AccountGrant.UNLOCKS);
        var issued = sessions.establish(proof("account-a", grants));
        var service = new AccountAccessService(key -> proof("account-a", grants), sessions);

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
        var grants = Set.of(AccountGrant.ACCOUNT, AccountGrant.PROGRESSION, AccountGrant.UNLOCKS);
        var invalidSession = sessions.establish(proof("account-a", grants));
        var invalid = new AccountAccessService(key -> { throw new InvalidCredentialException("invalid"); }, sessions);
        assertThrows(InvalidCredentialException.class, () -> invalid.authorizeKeyedOperation(
                invalidSession.sessionId(), invalidSession.context(), "key", AccountOperation.LUCK_READ));
        assertFalse(sessions.resolve(invalidSession.sessionId(), invalidSession.context().accessContext()).authenticated());

        var transientSession = sessions.establish(proof("account-a", grants));
        var unavailable = new AccountAccessService(key -> { throw new ProofUnavailableException("offline", java.time.Duration.ofSeconds(5)); }, sessions);
        assertThrows(ProofUnavailableException.class, () -> unavailable.authorizeKeyedOperation(
                transientSession.sessionId(), transientSession.context(), "key", AccountOperation.LUCK_READ));
        assertTrue(sessions.resolve(transientSession.sessionId(), transientSession.context().accessContext()).authenticated());
    }

    @Test
    void liveLeaseKeepsAuthorizingDuringOutageButRenewalFailsAndExpiryIsNotRescued() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        var sessions = new AccountSessions(clock, 4);
        var issued = sessions.establish(proof("account-a", Set.of(AccountGrant.ACCOUNT)));
        var service = new AccountAccessService(key -> {
            throw new ProofUnavailableException("down", java.time.Duration.ofSeconds(5));
        }, sessions);

        time.set(NOW.plusSeconds(600));
        assertTrue(service.resolve(issued.sessionId(), issued.context().accessContext()).authenticated());
        assertThrows(ProofUnavailableException.class, () -> service.renew(issued.sessionId(),
                issued.context().accessContext(), "key"));
        assertTrue(service.resolve(issued.sessionId(), issued.context().accessContext()).authenticated());

        time.set(NOW.plusSeconds(15 * 60));
        assertEquals(AccountSessions.Outcome.EXPIRED,
                service.resolve(issued.sessionId(), issued.context().accessContext()).outcome());
    }

    @Test
    void accountAndBResolveDistinctlyAndRejectionInvokesNoAccountWork() {
        var sessions = new AccountSessions(Clock.fixed(NOW, ZoneOffset.UTC), 4);
        var a = sessions.establish(proof("account-a", Set.of(AccountGrant.ACCOUNT)));
        var b = sessions.establish(proof("account-b", Set.of(AccountGrant.ACCOUNT)));
        var service = new AccountAccessService(key -> proof("account-a", Set.of(AccountGrant.ACCOUNT)), sessions);
        var work = new java.util.ArrayList<String>();
        java.util.function.BiConsumer<String, String> request = (cookie, context) -> {
            var r = service.resolve(cookie, context);
            if (r.authenticated()) work.add(r.context().accountId());
        };

        request.accept(a.sessionId(), a.context().accessContext());
        request.accept(b.sessionId(), b.context().accessContext());
        assertEquals(java.util.List.of("account-a", "account-b"), work);

        request.accept(b.sessionId(), a.context().accessContext());
        request.accept(a.sessionId(), b.context().accessContext());
        request.accept(null, a.context().accessContext());
        request.accept(a.sessionId(), null);
        assertEquals(2, work.size());
    }

    private static AccountProof proof(String id, Set<AccountGrant> grants) {
        return new AccountProof(id, grants, Set.of(), null);
    }

    @Test
    void everyLuckPermissionIsRequiredBeforeProvingAnOperationKey() {
        for (var missing : Set.of(AccountGrant.ACCOUNT, AccountGrant.PROGRESSION, AccountGrant.UNLOCKS)) {
            var grants = java.util.EnumSet.of(AccountGrant.ACCOUNT, AccountGrant.PROGRESSION, AccountGrant.UNLOCKS);
            grants.remove(missing);
            var sessions = new AccountSessions(Clock.fixed(NOW, ZoneOffset.UTC), 4);
            var issued = sessions.establish(proof("account-a", grants));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var service = new AccountAccessService(key -> {
                calls.incrementAndGet();
                return proof("account-a", grants);
            }, sessions);
            assertThrows(AccountAccessService.InsufficientGrantsException.class,
                    () -> service.authorizeKeyedOperation(issued.sessionId(), issued.context(), "key",
                            AccountOperation.LUCK_READ), "missing " + missing);
            assertEquals(0, calls.get());
        }
    }

    @Test
    void fullRefreshRejectsMissingLuckPermissionOrEndpointBeforeUpstreamProof() {
        var full = Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES, AccountGrant.CHARACTERS,
                AccountGrant.UNLOCKS, AccountGrant.PROGRESSION);
        var withoutProgression = java.util.EnumSet.copyOf(full);
        withoutProgression.remove(AccountGrant.PROGRESSION);
        var sessions = new AccountSessions(Clock.fixed(NOW, ZoneOffset.UTC), 4);
        var narrowGrants = sessions.establish(proof("account-a", withoutProgression));
        var narrowUrls = sessions.establish(new AccountProof("account-a", full,
                Set.of("/v2/account", "/v2/account/bank", "/v2/account/materials",
                        "/v2/account/recipes", "/v2/characters"), null));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var service = new AccountAccessService(key -> {
            calls.incrementAndGet();
            return proof("account-a", full);
        }, sessions);
        for (var issued : java.util.List.of(narrowGrants, narrowUrls)) {
            assertThrows(AccountAccessService.InsufficientGrantsException.class,
                    () -> service.authorizeKeyedOperation(issued.sessionId(), issued.context(), "key",
                            AccountOperation.ACCOUNT_REFRESH));
        }
        assertEquals(0, calls.get());
    }
}
