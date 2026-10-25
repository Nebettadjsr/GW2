package infra.icons;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical-source rules of TARGET_ARCHITECTURE.md §12.1 (STORY-API-009): which retained metadata
 * is acceptable, how it is canonicalized, and which key and extension it derives.
 *
 * <p>The expected keys are precomputed SHA-256 digests written out literally rather than recomputed
 * here, so a change to the key derivation fails this test instead of silently agreeing with itself -
 * a changed key would invalidate every already-cached entry and every URL a browser holds.
 */
class IconSourcePolicyTest {

    private static final String PNG_SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final String PNG_KEY = "50dc20284b8e24f872bc768471d8ec57efa59bb49b012531b2293dc8c50e6472";

    private static final String JPG_SOURCE = "https://render.guildwars2.com/file/FEDCBA9876543210/4321.jpg";
    private static final String JPG_KEY = "9247ad5f6cd4702a0c12724f8a90235191e8831a61be53d54a138b86e3984f8d";

    // ---------- accepted sources ----------

    @Test
    void acceptsARenderUrlAndDerivesItsKeyAndExtension() {
        IconSource source = accepted(PNG_SOURCE);

        assertEquals(PNG_SOURCE, source.canonicalUrl());
        assertEquals(PNG_KEY, source.sourceKey());
        assertEquals("png", source.extension());
    }

    @Test
    void acceptsAJpgSourceAndKeepsItsOwnExtension() {
        IconSource source = accepted(JPG_SOURCE);

        assertEquals(JPG_KEY, source.sourceKey());
        assertEquals("jpg", source.extension());
    }

    @Test
    void canonicalizesSchemeAndHostCaseAndDropsAnExplicitPort443() {
        assertEquals(PNG_SOURCE, accepted("HTTPS://Render.GuildWars2.com/file/ABCDEF0123456789/1234.png").canonicalUrl());
        assertEquals(PNG_SOURCE, accepted("https://render.guildwars2.com:443/file/ABCDEF0123456789/1234.png").canonicalUrl());

        assertEquals(PNG_KEY, accepted("https://RENDER.GUILDWARS2.COM:443/file/ABCDEF0123456789/1234.png").sourceKey(),
                "two spellings of the same source must share one key, or the same image would be cached twice");
    }

    @Test
    void retainsTheAcceptedPathSpellingIncludingSignatureCase() {
        String mixedCaseSignature = "https://render.guildwars2.com/file/AbCdEf0123456789/1234.png";

        assertEquals(mixedCaseSignature, accepted(mixedCaseSignature).canonicalUrl());
        assertTrue(accepted(mixedCaseSignature).sourceKey().matches("[0-9a-f]{64}"));
    }

    @Test
    void aChangedSourceDerivesADifferentKey() {
        assertFalse(accepted(PNG_SOURCE).sourceKey().equals(
                        accepted("https://render.guildwars2.com/file/ABCDEF0123456789/9999.png").sourceKey()),
                "a new upstream version must become a new key, never overwrite the old one");
    }

    // ---------- rejected sources ----------

    @Test
    void rejectsAbsentMetadata() {
        assertTrue(IconSourcePolicy.accept(null).isEmpty());
        assertTrue(IconSourcePolicy.accept("").isEmpty());
        assertTrue(IconSourcePolicy.accept("   ").isEmpty());
    }

    @Test
    void rejectsAnyOtherHostSchemeOrPath() {
        assertRejected("http://render.guildwars2.com/file/ABCDEF0123456789/1234.png", "plain HTTP");
        assertRejected("https://cdn.example.com/file/ABCDEF0123456789/1234.png", "another host");
        assertRejected("https://render.guildwars2.com.evil.test/file/ABCDEF0123456789/1234.png", "a host suffix");
        assertRejected("https://evil.test/render.guildwars2.com/file/ABCDEF0123456789/1234.png", "the host in a path");
        assertRejected("https://render.guildwars2.com/other/ABCDEF0123456789/1234.png", "a non-file path");
        assertRejected("https://render.guildwars2.com/file/ABCDEF0123456789/1234.gif", "an unsupported extension");
        assertRejected("https://render.guildwars2.com/file/ABCDEF0123456789/1234.PNG", "an uppercase extension");
        assertRejected("https://render.guildwars2.com/file/NOTHEX/1234.png", "a non-hexadecimal signature");
        assertRejected("https://render.guildwars2.com/file//1234.png", "an empty signature");
        assertRejected("https://render.guildwars2.com/file/ABCDEF0123456789/0.png", "a zero file id");
        assertRejected("https://render.guildwars2.com/file/ABCDEF0123456789/-5.png", "a negative file id");
        assertRejected("https://render.guildwars2.com/file/ABCDEF0123456789/abc.png", "a non-numeric file id");
        assertRejected("/file/ABCDEF0123456789/1234.png", "a relative URL");
        assertRejected("not a url at all", "text that is not a URL");
    }

    @Test
    void rejectsUserinfoNondefaultPortsQueriesFragmentsEncodingAndDotSegments() {
        assertRejected("https://user:pass@render.guildwars2.com/file/ABCDEF0123456789/1234.png", "userinfo");
        assertRejected("https://render.guildwars2.com:8443/file/ABCDEF0123456789/1234.png", "a nondefault port");
        assertRejected("https://render.guildwars2.com/file/ABCDEF0123456789/1234.png?v=2", "a query");
        assertRejected("https://render.guildwars2.com/file/ABCDEF0123456789/1234.png#top", "a fragment");
        assertRejected("https://render.guildwars2.com/file%2FABCDEF0123456789/1234.png", "an encoded separator");
        assertRejected("https://render.guildwars2.com/file/../file/ABCDEF0123456789/1234.png", "dot segments");
        assertRejected("https://render.guildwars2.com/file/ABCDEF0123456789/../1234.png", "a parent segment");
    }

    // ---------- route-shaped checks ----------

    @Test
    void recognizesOnlyLowercase64HexAsASourceKey() {
        assertTrue(IconSourcePolicy.isSourceKey(PNG_KEY));
        assertFalse(IconSourcePolicy.isSourceKey(PNG_KEY.toUpperCase()));
        assertFalse(IconSourcePolicy.isSourceKey(PNG_KEY.substring(1)), "63 characters");
        assertFalse(IconSourcePolicy.isSourceKey(PNG_KEY + "0"), "65 characters");
        assertFalse(IconSourcePolicy.isSourceKey("../../etc/passwd"));
        assertFalse(IconSourcePolicy.isSourceKey(null));
    }

    @Test
    void recognizesOnlyTheTwoAcceptedExtensions() {
        assertTrue(IconSourcePolicy.isExtension("png"));
        assertTrue(IconSourcePolicy.isExtension("jpg"));
        assertFalse(IconSourcePolicy.isExtension("PNG"));
        assertFalse(IconSourcePolicy.isExtension("jpeg"));
        assertFalse(IconSourcePolicy.isExtension("svg"));
        assertFalse(IconSourcePolicy.isExtension(null));
    }

    @Test
    void mapsEachExtensionToItsContentType() {
        assertEquals("image/png", IconSourcePolicy.contentTypeOf("png"));
        assertEquals("image/jpeg", IconSourcePolicy.contentTypeOf("jpg"));
    }

    private static IconSource accepted(String rawUrl) {
        Optional<IconSource> source = IconSourcePolicy.accept(rawUrl);
        assertTrue(source.isPresent(), "expected an accepted source: " + rawUrl);
        return source.get();
    }

    private static void assertRejected(String rawUrl, String why) {
        assertTrue(IconSourcePolicy.accept(rawUrl).isEmpty(), "must reject " + why + ": " + rawUrl);
    }
}
