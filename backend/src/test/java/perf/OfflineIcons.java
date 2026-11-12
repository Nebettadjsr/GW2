package perf;

import application.icons.IconDelivery;
import infra.icons.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only owned offline image edge for the capacity experiment (STORY-KPI-002). The real
 * {@link IconDelivery}, {@link IconAcquisition}, controllers and metadata repositories run; only the two
 * outer edges are replaced: the store is in memory (no configured user icon-store directory is read or
 * written) and the "upstream" is a deterministic local byte source ({@code HttpIconImageFetcher} is never
 * constructed). Production wiring is unchanged and this configuration is only imported by the experiment.
 *
 * <p><b>Asset-fidelity limits:</b> every icon is the same 1x1 PNG, so image decode/paint/transfer cost and
 * byte size are NOT representative of real ArenaNet art (only request routing, cache hit/miss and
 * delivery-boundary behavior are). Real icons are larger, so image bytes are understated.
 */
@TestConfiguration
public class OfflineIcons {
    public static final String FIDELITY = "offline icons: deterministic 1x1 PNG for every item/resource, served by the real IconDelivery over an in-memory store; "
            + "image size/decode/paint cost is NOT representative of real ArenaNet art; no HttpIconImageFetcher, no configured icon-store writes";
    static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /** Canonical sources the offline edge was asked to supply: never fetched from any network. */
    public static final List<String> OFFLINE_ACQUISITIONS = new CopyOnWriteArrayList<>();

    /** In-memory {@link IconStore}: nothing is written to disk. */
    static final class MemoryStore implements IconStore {
        private final ConcurrentHashMap<String, byte[]> map = new ConcurrentHashMap<>();
        @Override public Optional<StoredIcon> find(String key, String ext) {
            byte[] b = map.get(key + "." + ext);
            return b == null ? Optional.empty() : Optional.of(new StoredIcon(null, b));
        }
        @Override public StoredIcon publish(String key, String ext, byte[] bytes) {
            byte[] kept = map.computeIfAbsent(key + "." + ext, k -> bytes.clone());
            return new StoredIcon(null, kept);
        }
    }

    static final class OfflineFetcher implements IconImageFetcher {
        @Override public IconFetchResult fetch(IconSource source) {
            OFFLINE_ACQUISITIONS.add(source.sourceKey());
            return new IconFetchResult.Fetched(PNG.clone());
        }
    }

    @Bean @Primary
    IconDelivery offlineIconDelivery() {
        MemoryStore store = new MemoryStore();
        return new IconDelivery(new repo.ItemIconMetadataRepository(), store,
                new IconAcquisition(store, new OfflineFetcher()), new repo.BuildMetadataRepository());
    }
}
