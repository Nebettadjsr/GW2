package application.icons;

import infra.icons.IconAcquisition;
import infra.icons.IconAcquisitionResult;
import infra.icons.IconCacheBounds;
import infra.icons.IconSource;
import infra.icons.IconSourcePolicy;
import infra.icons.IconStorageException;
import infra.icons.IconStore;
import infra.icons.StoredIcon;
import repo.ItemIconMetadataRepository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Optional;

/**
 * The application icon-delivery boundary of TARGET_ARCHITECTURE.md §12.1: it decides what one image
 * request may produce, coordinating the persistent filesystem cache, the retained metadata and the
 * upstream adapter behind it. The HTTP controller over it only translates the outcome into a response.
 *
 * <p>The order of steps is the contract, not an implementation detail:
 *
 * <ol>
 *   <li>Validate the route's own values. A malformed route never reaches metadata or the filesystem.</li>
 *   <li>Read the cache. A valid hit is served with no upstream request and no metadata read at all, so
 *       cached icons keep working while the upstream host or the database is unavailable.</li>
 *   <li>On a miss, load the item's retained source and require the key and extension it derives to
 *       equal the requested ones. An unknown item, absent or rejected metadata, and an obsolete or
 *       mismatched key are 404 without any upstream access.</li>
 *   <li>Only then acquire the matched canonical source, which publishes before reporting success.</li>
 * </ol>
 *
 * <p>Because the key is derived from the source, a changed source produces a new key and a new browser
 * URL; an old key keeps serving its original image and can never be handed new-version bytes.
 *
 * <p>Stateless apart from its collaborators, and safe for concurrent requests.
 */
public class IconDelivery {

    private final ItemIconMetadataRepository metadataRepo;
    private final IconStore store;
    private final IconAcquisition acquisition;

    public IconDelivery(ItemIconMetadataRepository metadataRepo, IconStore store, IconAcquisition acquisition) {
        this.metadataRepo = metadataRepo;
        this.store = store;
        this.acquisition = acquisition;
    }

    /**
     * Delivers the image the route names.
     *
     * @param rawItemId the {@code itemId} path segment as text
     * @param fileName  the {@code {sourceKey}.{ext}} path segment
     */
    public IconDeliveryResult deliver(String rawItemId, String fileName) {
        Optional<ItemIconUrls.ItemIconRequest> parsed = ItemIconUrls.parse(rawItemId, fileName);
        if (parsed.isEmpty()) {
            return new IconDeliveryResult.InvalidRequest("the icon route's item id, key or extension is malformed");
        }

        ItemIconUrls.ItemIconRequest request = parsed.get();

        Optional<StoredIcon> hit;
        try {
            hit = store.find(request.sourceKey(), request.extension());
        } catch (IconStorageException storageFailure) {
            return unavailable("icon storage is unavailable");
        }

        if (hit.isPresent()) return delivered(hit.get(), request.extension());

        return acquireForMiss(request);
    }

    private IconDeliveryResult acquireForMiss(ItemIconUrls.ItemIconRequest request) {
        Optional<String> retainedSource;
        try {
            retainedSource = metadataRepo.findIconSource(request.itemId());
        } catch (SQLException metadataUnavailable) {
            return unavailable("icon metadata is unavailable");
        }

        if (retainedSource.isEmpty()) {
            return new IconDeliveryResult.NotFound("no retained icon metadata for this item");
        }

        Optional<IconSource> source = IconSourcePolicy.accept(retainedSource.get());
        if (source.isEmpty()) {
            return new IconDeliveryResult.NotFound("the item's retained icon source is not an accepted source");
        }

        if (!matchesRequest(source.get(), request)) {
            // The key the route asked for is not the one this item's current source derives: an
            // obsolete or foreign key, answered without any upstream access.
            return new IconDeliveryResult.NotFound("the requested icon key does not match the item's source");
        }

        return switch (acquisition.acquire(source.get())) {
            case IconAcquisitionResult.Available(StoredIcon icon) -> delivered(icon, request.extension());
            case IconAcquisitionResult.UpstreamMissing ignored ->
                    new IconDeliveryResult.NotFound("the image does not exist upstream");
            case IconAcquisitionResult.Unavailable(String reason) -> unavailable(reason);
        };
    }

    private static boolean matchesRequest(IconSource source, ItemIconUrls.ItemIconRequest request) {
        return source.sourceKey().equals(request.sourceKey()) && source.extension().equals(request.extension());
    }

    private static IconDeliveryResult delivered(StoredIcon icon, String extension) {
        return new IconDeliveryResult.Delivered(
                icon.bytes(), IconSourcePolicy.contentTypeOf(extension), strongEtag(icon.bytes()));
    }

    private static IconDeliveryResult unavailable(String reason) {
        return new IconDeliveryResult.Unavailable(reason, IconCacheBounds.RETRY_AFTER_SECONDS);
    }

    /**
     * Derived from the stored bytes rather than from the key, so it is a strong validator in the HTTP
     * sense: two responses share it only when their bodies are byte-identical.
     */
    private static String strongEtag(byte[] bytes) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }

        byte[] digest = sha256.digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return "\"" + hex + "\"";
    }
}
