package application.icons;

import infra.icons.IconSource;
import infra.icons.IconSourcePolicy;
import model.BuildResourceType;

import java.util.Optional;

/**
 * Application-relative image URL of a build reference resource:
 * {@code /api/resources/{type}/{id}/icon/{sourceKey}.{ext}} (STORY-CHAR-009). The resource type is part of
 * the route and of the metadata lookup, so overlapping numeric ids of different types cannot collide;
 * the validated image bytes still share the one content-keyed cache.
 */
public final class ResourceIconUrls {

    private static final String PREFIX = "/api/resources/";

    private ResourceIconUrls() {}

    public record ResourceIconRequest(BuildResourceType type, int id, String sourceKey, String extension) {}

    /** The icon URL, or null when there is no acceptable retained source. */
    public static String iconUrlFor(BuildResourceType type, int id, String rawIconSource) {
        Optional<IconSource> source = IconSourcePolicy.accept(rawIconSource);
        if (source.isEmpty()) return null;
        return PREFIX + type.segment() + "/" + id + "/icon/" + source.get().sourceKey() + "."
                + source.get().extension();
    }

    public static Optional<ResourceIconRequest> parse(String rawType, String rawId, String fileName) {
        if (rawType == null || rawId == null || fileName == null) return Optional.empty();
        Optional<BuildResourceType> type = BuildResourceType.ofSegment(rawType);
        if (type.isEmpty()) return Optional.empty();
        int id;
        try {
            id = Integer.parseInt(rawId);
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
        if (id <= 0) return Optional.empty();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return Optional.empty();
        String key = fileName.substring(0, dot);
        String ext = fileName.substring(dot + 1);
        if (!IconSourcePolicy.isSourceKey(key) || !IconSourcePolicy.isExtension(ext)) return Optional.empty();
        return Optional.of(new ResourceIconRequest(type.get(), id, key, ext));
    }
}
