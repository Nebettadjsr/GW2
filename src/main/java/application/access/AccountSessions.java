package application.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bounded, process-local store of validated account sessions (STORY-API-010,
 * TARGET_ARCHITECTURE.md §8.1, ADR-008).
 *
 * <p>Four properties this class exists to keep:
 *
 * <ul>
 *   <li><b>The session identifier is never retained.</b> Only its SHA-256 verifier is a key here, so
 *       a memory dump or a diagnostic of this map yields nothing a caller could present. The GW2 key
 *       is not passed in at all - a session is deliberately not an upstream credential.</li>
 *   <li><b>Expiry is absolute.</b> It is computed once, at proof, as the lease or the credential's own
 *       earlier expiry; {@link #resolve} only ever reads it. There is no touch, no sliding window and
 *       no renewal of an existing entry, so reads, polling and refresh success cannot extend a
 *       lease.</li>
 *   <li><b>Storage is bounded.</b> The oldest proof is evicted past the configured capacity, which
 *       makes the store's worst case independent of how many keys are tried. An evicted session is
 *       indistinguishable from an unknown one: both require new proof.</li>
 *   <li><b>The access context is checked here, against the exact entry.</b> A shared cookie scope
 *       means the cookie alone cannot say which account a tab believes it is using.</li>
 * </ul>
 */
public final class AccountSessions {

    /** TARGET_ARCHITECTURE.md §8.1: 15 minutes after successful upstream validation. */
    public static final Duration LEASE = Duration.ofMinutes(15);

    /** 256 bits, well above the 128-bit minimum the contract sets. */
    private static final int SESSION_ID_BYTES = 32;
    private static final int ACCESS_CONTEXT_BYTES = 16;

    private final Clock clock;
    private final int capacity;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SessionEntry> byVerifier;

    public AccountSessions(Clock clock, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("session capacity must be positive");
        this.clock = clock;
        this.capacity = capacity;
        this.byVerifier = new LinkedHashMap<>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, SessionEntry> eldest) {
                return size() > AccountSessions.this.capacity;
            }
        };
    }

    /** What proof produced: the one-time identifier for the cookie, and the resolved scope. */
    public record Issued(String sessionId, AccountContext context) {}

    public enum Outcome {
        /** A live session whose access context matched; {@link Resolution#context()} is set. */
        AUTHENTICATED,
        /** No cookie, or an unknown/evicted identifier. New proof required. */
        NO_SESSION,
        /** Known but past its absolute expiry; removed on this read. New proof required. */
        EXPIRED,
        /** Live session, but the asserted access context is missing or belongs elsewhere. */
        CONTEXT_MISMATCH
    }

    /** {@code context} is non-null only for {@link Outcome#AUTHENTICATED}. */
    public record Resolution(Outcome outcome, AccountContext context) {
        public boolean authenticated() {
            return outcome == Outcome.AUTHENTICATED;
        }
    }

    /**
     * Issues a fresh session for an already successful proof. Never reuses an identifier, an access
     * context or an existing entry: a replacement key gets its own session under its own grants.
     */
    public synchronized Issued establish(AccountProof proof) {
        Instant now = clock.instant();
        Instant expiresAt = earliest(now.plus(LEASE), proof.credentialExpiresAt());
        String sessionId = randomToken(SESSION_ID_BYTES);
        String accessContext = randomToken(ACCESS_CONTEXT_BYTES);
        AccountContext context = new AccountContext(proof.accountId(), proof.grants(),
                proof.endpointRestrictions(), accessContext, expiresAt);
        purgeExpired(now);
        byVerifier.put(verifierOf(sessionId), new SessionEntry(context, now));
        return new Issued(sessionId, context);
    }

    /**
     * Resolves a presented cookie value and asserted access context without changing either the
     * entry's expiry or its eviction position.
     */
    public synchronized Resolution resolve(String sessionId, String assertedContext) {
        if (sessionId == null || sessionId.isBlank()) return new Resolution(Outcome.NO_SESSION, null);
        String verifier = verifierOf(sessionId);
        SessionEntry entry = byVerifier.get(verifier);
        if (entry == null) return new Resolution(Outcome.NO_SESSION, null);
        if (!clock.instant().isBefore(entry.context().expiresAt())) {
            byVerifier.remove(verifier);
            return new Resolution(Outcome.EXPIRED, null);
        }
        if (assertedContext == null || assertedContext.isBlank()
                || !constantTimeEquals(entry.context().accessContext(), assertedContext)) {
            return new Resolution(Outcome.CONTEXT_MISMATCH, null);
        }
        return new Resolution(Outcome.AUTHENTICATED, entry.context());
    }

    /**
     * Revokes the identified session. Returns false when nothing was live under that identifier, so
     * a caller never reports a revocation it did not perform.
     */
    public synchronized boolean revoke(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return byVerifier.remove(verifierOf(sessionId)) != null;
    }

    /**
     * A private capability that revokes exactly this session later without keeping the raw
     * identifier: it captures only the stored verifier.
     */
    public Runnable revocationOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return () -> { };
        String verifier = verifierOf(sessionId);
        return () -> {
            synchronized (this) {
                byVerifier.remove(verifier);
            }
        };
    }

    /** Live entry count, for bounded-storage verification; never exposes a session identifier. */
    public synchronized int size() {
        purgeExpired(clock.instant());
        return byVerifier.size();
    }

    private void purgeExpired(Instant now) {
        byVerifier.values().removeIf(entry -> !now.isBefore(entry.context().expiresAt()));
    }

    private static Instant earliest(Instant lease, Instant credentialExpiry) {
        if (credentialExpiry == null) return lease;
        return credentialExpiry.isBefore(lease) ? credentialExpiry : lease;
    }

    private String randomToken(int bytes) {
        byte[] material = new byte[bytes];
        random.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    private static String verifierOf(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to store session verifiers", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** @param provedAt validation time, kept for diagnostics; the authority on lifetime is the expiry. */
    private record SessionEntry(AccountContext context, Instant provedAt) {}
}
