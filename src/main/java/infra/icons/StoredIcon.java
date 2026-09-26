package infra.icons;

import java.nio.file.Path;

/**
 * A fully committed cache entry (TARGET_ARCHITECTURE.md §12.1): validated image bytes and the file
 * they were read from or published to.
 *
 * <p>{@code file} exists for the desktop path only - {@code items.icon_path} names a local file for
 * in-process JavaFX. It must never reach a browser response.
 */
public record StoredIcon(Path file, byte[] bytes) {
}
