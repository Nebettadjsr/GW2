package infra.icons;

/**
 * Acquires the image bytes of one accepted canonical source (TARGET_ARCHITECTURE.md §12.1).
 *
 * <p>Implementations may fetch only {@link IconSource#canonicalUrl()} - never a caller-supplied URL,
 * path or host - must not follow redirects, and must forward no API key, cookie, application
 * authorization or account data. They report failure as a result rather than by throwing, and they
 * never return bytes they have not validated as a complete image of the source's extension.
 */
public interface IconImageFetcher {

    IconFetchResult fetch(IconSource source);
}
