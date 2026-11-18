package infra.icons;

/**
 * One accepted canonical upstream image source.
 *
 * <p>This is a public record and does not validate itself, so holding one proves nothing by itself.
 * Production callers obtain instances from {@link IconSourcePolicy}, which guarantees that the URL is
 * an absolute HTTPS {@code render.guildwars2.com} render URL, the key is the lowercase 64-hex SHA-256
 * of that URL's UTF-8 bytes, and the extension is lowercase {@code png} or {@code jpg}. Tests may
 * construct fixture sources directly (for example pointing at a local server).
 *
 * @param canonicalUrl the canonical form (lowercase scheme/host, no explicit port 443, accepted
 *                     path spelling retained) - the only URL that may ever be fetched for this key
 * @param sourceKey    the source-versioned cache key derived from {@code canonicalUrl}
 * @param extension    the accepted lowercase extension, which is also the stored file's extension
 */
public record IconSource(String canonicalUrl, String sourceKey, String extension) {
}
