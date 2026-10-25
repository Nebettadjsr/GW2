package infra.icons;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place the canonical icon-source rules of TARGET_ARCHITECTURE.md §12.1 live: what counts as
 * an acceptable upstream image URL, how it is canonicalized, and which cache key and file extension
 * it derives. Both the emitted browser URL and the image endpoint's route check go through here, so a
 * key can never mean two different things on the two sides.
 *
 * <p>Accepted: a parsed absolute HTTPS URL on exactly {@code render.guildwars2.com} whose path is
 * {@code /file/{signature}/{file_id}.{ext}} with a hexadecimal signature, a positive decimal file id
 * and a lowercase {@code png} or {@code jpg} extension. Everything else is rejected - other
 * hosts/schemes/paths, userinfo, a nondefault port, a query, a fragment, percent-encoding (which is
 * how an encoded separator would arrive) and dot segments.
 *
 * <p>Rejection is not an error: metadata this policy does not accept simply yields no icon URL
 * (§12.1), which the caller reports as null. Stateless and thread-safe.
 */
public final class IconSourcePolicy {

    /** The only host whose images may be fetched. */
    public static final String RENDER_HOST = "render.guildwars2.com";

    public static final String EXTENSION_PNG = "png";
    public static final String EXTENSION_JPG = "jpg";

    private static final int HTTPS_DEFAULT_PORT = 443;

    private static final Pattern RENDER_PATH =
            Pattern.compile("/file/([0-9A-Fa-f]+)/([1-9][0-9]*)\\.(png|jpg)");

    /** Lowercase SHA-256 hex, which is what {@link #accept(String)} derives as the source key. */
    private static final Pattern SOURCE_KEY = Pattern.compile("[0-9a-f]{64}");

    private IconSourcePolicy() {}

    /**
     * The canonical source for {@code rawUrl}, or empty when the metadata is absent or unacceptable.
     *
     * @param rawUrl retained {@code items.icon_url} metadata; may be null or blank
     */
    public static Optional<IconSource> accept(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return Optional.empty();

        String candidate = rawUrl.trim();

        // Percent-encoding is rejected outright rather than decoded: an encoded separator must never
        // be able to describe a path this policy would otherwise refuse.
        if (candidate.indexOf('%') >= 0) return Optional.empty();

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException notAUrl) {
            return Optional.empty();
        }

        if (!uri.isAbsolute() || uri.isOpaque()) return Optional.empty();
        if (!"https".equalsIgnoreCase(uri.getScheme())) return Optional.empty();
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return Optional.empty();
        }
        if (uri.getPort() != -1 && uri.getPort() != HTTPS_DEFAULT_PORT) return Optional.empty();

        String host = uri.getHost();
        if (host == null || !RENDER_HOST.equals(host.toLowerCase(Locale.ROOT))) return Optional.empty();

        String path = uri.getRawPath();
        if (path == null) return Optional.empty();

        Matcher matcher = RENDER_PATH.matcher(path);
        if (!matcher.matches()) return Optional.empty();

        String canonicalUrl = "https://" + RENDER_HOST + path;
        return Optional.of(new IconSource(canonicalUrl, sourceKeyOf(canonicalUrl), matcher.group(3)));
    }

    /** Whether {@code sourceKey} has the shape this policy derives; a route check, not a lookup. */
    public static boolean isSourceKey(String sourceKey) {
        return sourceKey != null && SOURCE_KEY.matcher(sourceKey).matches();
    }

    /** Whether {@code extension} is one of the two accepted lowercase image extensions. */
    public static boolean isExtension(String extension) {
        return EXTENSION_PNG.equals(extension) || EXTENSION_JPG.equals(extension);
    }

    /** The {@code Content-Type} the accepted extension stands for. */
    public static String contentTypeOf(String extension) {
        if (EXTENSION_PNG.equals(extension)) return "image/png";
        if (EXTENSION_JPG.equals(extension)) return "image/jpeg";
        throw new IllegalArgumentException("not an accepted icon extension: " + extension);
    }

    private static String sourceKeyOf(String canonicalUrl) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }

        byte[] digest = sha256.digest(canonicalUrl.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
