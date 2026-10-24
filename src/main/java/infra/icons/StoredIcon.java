package infra.icons;

import java.nio.file.Path;

/**
 * A fully committed cache entry (TARGET_ARCHITECTURE.md §12.1): validated image bytes and the file
 * they were read from or published to.
 *
 * <p>{@code file} is the cache entry's own location on local disk. It is internal to the store and
 * must never reach a browser response, which carries the validated bytes alone.
 */
public record StoredIcon(Path file, byte[] bytes) {
}
