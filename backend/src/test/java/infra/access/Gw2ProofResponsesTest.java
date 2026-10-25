package infra.access;

import application.access.AccountGrant;
import application.access.AccountProof;
import application.access.InvalidCredentialException;
import application.access.MalformedProofException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Offline, trimmed /v2/account and /v2/tokeninfo shapes; no live ArenaNet request, no real key. */
class Gw2ProofResponsesTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String GUID = "AAAAAAAA-1111-2222-3333-BBBBBBBBBBBB";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ACCOUNT = "{\"id\":\"" + GUID + "\",\"name\":\"Someone.1234\"}";
    private static final String WIDE_TOKEN = "{\"id\":\"key-id\",\"name\":\"wide\","
            + "\"permissions\":[\"account\",\"inventories\",\"characters\",\"unlocks\",\"futureperm\"]}";

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static AccountProof parse(String account, String token) throws Exception {
        return Gw2ProofResponses.parse(json(account), json(token), CLOCK);
    }

    @Test
    void resolvesStableGuidAndIgnoresNameAndKeyId() throws Exception {
        AccountProof proof = parse(ACCOUNT, WIDE_TOKEN);
        assertEquals(GUID, proof.accountId());
        assertEquals(Set.of(AccountGrant.ACCOUNT, AccountGrant.INVENTORIES, AccountGrant.CHARACTERS,
                AccountGrant.UNLOCKS), proof.grants());
        assertTrue(proof.endpointRestrictions().isEmpty());
        assertNull(proof.credentialExpiresAt());
    }

    @Test
    void replacementKeyKeepsItsOwnGrantsForTheSameAccount() throws Exception {
        AccountProof wide = parse(ACCOUNT, WIDE_TOKEN);
        AccountProof narrow = parse(ACCOUNT, "{\"id\":\"other\",\"permissions\":[\"account\"]}");
        assertEquals(wide.accountId(), narrow.accountId());
        assertEquals(Set.of(AccountGrant.ACCOUNT), narrow.grants());
    }

    @Test
    void restrictedAndExpiringKeyCarriesUrlsAndExpiry() throws Exception {
        AccountProof proof = parse(ACCOUNT, "{\"permissions\":[\"account\",\"inventories\"],"
                + "\"urls\":[\"/v2/account/bank\"],\"expires_at\":\"2026-01-01T00:05:00Z\"}");
        assertEquals(Set.of("/v2/account/bank"), proof.endpointRestrictions());
        assertEquals(NOW.plusSeconds(300), proof.credentialExpiresAt());
    }

    @Test
    void expiredKeyIsDefinitivelyInvalid() {
        assertThrows(InvalidCredentialException.class, () -> parse(ACCOUNT,
                "{\"permissions\":[\"account\"],\"expires_at\":\"2026-01-01T00:00:00Z\"}"));
    }

    @Test
    void malformedResponsesFailClosedWithoutQuotingBodies() {
        String[][] cases = {
                {"{\"name\":\"x\"}", WIDE_TOKEN},
                {"{\"id\":\"not-a-guid\"}", WIDE_TOKEN},
                {"{\"id\":42}", WIDE_TOKEN},
                {"[]", WIDE_TOKEN},
                {ACCOUNT, "{\"id\":\"k\"}"},
                {ACCOUNT, "{\"permissions\":\"account\"}"},
                {ACCOUNT, "{\"permissions\":[1]}"},
                {ACCOUNT, "{\"permissions\":[\"account\"],\"urls\":\"/v2/account\"}"},
                {ACCOUNT, "{\"permissions\":[\"account\"],\"urls\":[7]}"},
                {ACCOUNT, "{\"permissions\":[\"account\"],\"expires_at\":\"tomorrow\"}"},
                {ACCOUNT, "{\"permissions\":[\"account\"],\"expires_at\":5}"},
        };
        for (String[] c : cases) {
            MalformedProofException e = assertThrows(MalformedProofException.class, () -> parse(c[0], c[1]),
                    c[0] + " / " + c[1]);
            assertFalse(e.getMessage().contains("Someone.1234"));
            assertFalse(e.getMessage().contains(GUID));
        }
    }

    @Test
    void nullResponsesFailClosed() {
        assertThrows(MalformedProofException.class, () -> Gw2ProofResponses.parse(null, null, CLOCK));
    }
}
