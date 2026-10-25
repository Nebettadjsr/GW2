package application.icons;

import infra.icons.IconSource;
import infra.icons.IconSourcePolicy;

import java.util.Optional;

/**
 * The application-relative icon URL of TARGET_ARCHITECTURE.md §12.1 -
 * {@code /api/items/{itemId}/icon/{sourceKey}.{ext}} - in one place: it is both formatted and parsed
 * here, so what a read emits and what the image endpoint accepts cannot drift apart.
 *
 * <p>The URL is relative on purpose. The browser resolves it against the application's own origin
 * through existing API routing, which is why no upstream URL, host or filesystem path ever reaches a
 * response.
 *
 * <p>Stateless and thread-safe. Contains no HTTP machinery: this is a string contract, which is what
 * lets the transport mappers and the endpoint share it without either importing the other.
 */
public final class ItemIconUrls {

    private static final String ITEMS_PREFIX = "/api/items/";
    private static final String ICON_SEGMENT = "/icon/";

    private ItemIconUrls() {}

    /** One validated image request: what the route named, after it was proven well-formed. */
    public record ItemIconRequest(int itemId, String sourceKey, String extension) {
    }

    /**
     * The icon URL for an item, or null when there is none to offer.
     *
     * <p>Null covers every "no image" case alike - no item, no retained metadata, or metadata the
     * canonical-source policy rejects - because a consumer treats them identically and none of them
     * affects a calculation.
     *
     * @param itemId        the item the icon belongs to; null for an entry that has no item
     * @param rawIconSource retained {@code items.icon_url} metadata; may be null or unacceptable
     */
    public static String iconUrlFor(Integer itemId, String rawIconSource) {
        if (itemId == null) return null;

        Optional<IconSource> source = IconSourcePolicy.accept(rawIconSource);
        if (source.isEmpty()) return null;

        return ITEMS_PREFIX + itemId + ICON_SEGMENT + source.get().sourceKey() + "." + source.get().extension();
    }

    /**
     * The request the route's own values describe, or empty when any of them is malformed - which the
     * endpoint answers with 400 before it touches metadata or the filesystem.
     *
     * @param rawItemId the {@code itemId} path segment, as text, so a non-numeric value is this
     *                  policy's decision rather than a framework conversion failure
     * @param fileName  the {@code {sourceKey}.{ext}} path segment
     */
    public static Optional<ItemIconRequest> parse(String rawItemId, String fileName) {
        if (rawItemId == null || fileName == null) return Optional.empty();

        int itemId;
        try {
            itemId = Integer.parseInt(rawItemId);
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
        if (itemId <= 0) return Optional.empty();

        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return Optional.empty();

        String sourceKey = fileName.substring(0, dot);
        String extension = fileName.substring(dot + 1);

        if (!IconSourcePolicy.isSourceKey(sourceKey) || !IconSourcePolicy.isExtension(extension)) {
            return Optional.empty();
        }

        return Optional.of(new ItemIconRequest(itemId, sourceKey, extension));
    }
}
