package application.icons;

import infra.icons.FilesystemIconStore;
import infra.icons.IconAcquisition;
import infra.icons.IconFetchResult;
import infra.icons.IconImageFetcher;
import infra.icons.IconSource;
import infra.icons.IconSourcePolicy;
import model.BuildResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import repo.BuildMetadataRepository;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Typed resource image boundary (STORY-CHAR-009): route validation, hits, source verification, failures. */
class ResourceIconDeliveryTest {
    private static final String SOURCE = "https://render.guildwars2.com/file/ABCDEF0123456789/1234.png";
    private static final String KEY = IconSourcePolicy.accept(SOURCE).orElseThrow().sourceKey();

    @TempDir Path root;

    private static final class Stub extends BuildMetadataRepository {
        final Map<String, String> sources;
        final AtomicInteger calls = new AtomicInteger();
        boolean fail;
        Stub(Map<String, String> sources) { this.sources = sources; }
        @Override public Optional<String> findIconSource(BuildResourceType type, int id) throws SQLException {
            calls.incrementAndGet();
            if (fail) throw new SQLException("down");
            return Optional.ofNullable(sources.get(type.segment() + "/" + id));
        }
    }

    private static final class Fetcher implements IconImageFetcher {
        final AtomicInteger calls = new AtomicInteger();
        final IconFetchResult result;
        Fetcher(IconFetchResult result) { this.result = result; }
        public IconFetchResult fetch(IconSource source) { calls.incrementAndGet(); return result; }
    }

    private IconDelivery delivery(Stub stub, Fetcher fetcher) {
        var store = new FilesystemIconStore(root);
        return new IconDelivery(new repo.ItemIconMetadataRepository(), store, new IconAcquisition(store, fetcher), stub);
    }

    @Test
    void urlFormatAndParseAreInverseAndTypeIsPartOfIdentity() {
        String url = ResourceIconUrls.iconUrlFor(BuildResourceType.SKILLS, 7, SOURCE);
        assertEquals("/api/resources/skills/7/icon/" + KEY + ".png", url);
        assertNotEquals(url, ResourceIconUrls.iconUrlFor(BuildResourceType.TRAITS, 7, SOURCE));
        assertNull(ResourceIconUrls.iconUrlFor(BuildResourceType.PETS, 7, "https://evil.example/x.png"));
        assertNull(ResourceIconUrls.iconUrlFor(BuildResourceType.PETS, 7, null));
        var parsed = ResourceIconUrls.parse("skills", "7", KEY + ".png").orElseThrow();
        assertEquals(BuildResourceType.SKILLS, parsed.type());
        assertTrue(ResourceIconUrls.parse("legends", "7", KEY + ".png").isEmpty());
        assertTrue(ResourceIconUrls.parse("skills", "0", KEY + ".png").isEmpty());
        assertTrue(ResourceIconUrls.parse("skills", "7", "../x.png").isEmpty());
    }

    @Test
    void malformedRouteTouchesNeitherMetadataNorUpstream() {
        var stub = new Stub(Map.of());
        var fetcher = new Fetcher(new IconFetchResult.TemporaryFailure("x"));
        assertInstanceOf(IconDeliveryResult.InvalidRequest.class, delivery(stub, fetcher).deliverResource("items", "7", KEY + ".png"));
        assertEquals(0, stub.calls.get());
        assertEquals(0, fetcher.calls.get());
    }

    @Test
    void missWithMismatchedOrMissingSourceIsNotFoundWithoutUpstreamAccess() {
        var fetcher = new Fetcher(new IconFetchResult.TemporaryFailure("x"));
        var stub = new Stub(Map.of("traits/7", SOURCE));
        // same numeric id, other namespace: not found
        assertInstanceOf(IconDeliveryResult.NotFound.class, delivery(stub, fetcher).deliverResource("skills", "7", KEY + ".png"));
        // right namespace, obsolete key
        String other = IconSourcePolicy.accept("https://render.guildwars2.com/file/ABCDEF0123456789/9.png").orElseThrow().sourceKey();
        assertInstanceOf(IconDeliveryResult.NotFound.class, delivery(stub, fetcher).deliverResource("traits", "7", other + ".png"));
        assertEquals(0, fetcher.calls.get());
    }

    @Test
    void metadataFailureOnMissIsUnavailableButHitNeedsNoMetadata() throws Exception {
        var stub = new Stub(Map.of());
        stub.fail = true;
        var fetcher = new Fetcher(new IconFetchResult.TemporaryFailure("x"));
        assertInstanceOf(IconDeliveryResult.Unavailable.class, delivery(stub, fetcher).deliverResource("pets", "7", KEY + ".png"));
        new FilesystemIconStore(root).publish(KEY, "png", ImageFixtures.png());
        int before = stub.calls.get();
        assertInstanceOf(IconDeliveryResult.Delivered.class, delivery(stub, fetcher).deliverResource("pets", "7", KEY + ".png"));
        assertEquals(before, stub.calls.get());
        assertEquals(0, fetcher.calls.get());
    }

    @Test
    void verifiedMissAcquiresThroughSharedCacheAndNamespacesShareBytes() {
        byte[] png = ImageFixtures.png();
        var fetcher = new Fetcher(new IconFetchResult.Fetched(png));
        var stub = new Stub(Map.of("skills/7", SOURCE, "traits/7", SOURCE));
        var d = delivery(stub, fetcher);
        assertInstanceOf(IconDeliveryResult.Delivered.class, d.deliverResource("skills", "7", KEY + ".png"));
        assertInstanceOf(IconDeliveryResult.Delivered.class, d.deliverResource("traits", "7", KEY + ".png"));
        assertEquals(1, fetcher.calls.get(), "identical validated bytes are cached once across namespaces");
    }

    @Test
    void downloadFailureIsUnavailable() {
        var fetcher = new Fetcher(new IconFetchResult.TemporaryFailure("down"));
        var d = delivery(new Stub(Map.of("pets/7", SOURCE)), fetcher);
        assertInstanceOf(IconDeliveryResult.Unavailable.class, d.deliverResource("pets", "7", KEY + ".png"));
    }
}
