package infra.icons;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistent icon cache on local disk (TARGET_ARCHITECTURE.md §12.1): one file per canonical
 * source at {@code <ICON_CACHE_DIR>/assets-v1/{sourceKey}.{ext}}, so items sharing a source share one
 * binary and a changed source becomes a different file rather than an overwrite.
 *
 * <p>Every read is confined to the configured root: the resolved path must stay inside it and must be
 * a regular file that is not a symbolic link, checked before the bytes are touched. There is no
 * listing operation and no path is ever accepted from a caller - only a validated key and extension.
 *
 * <p>Every write uses the publication protocol: a unique temporary file beside the destination, then
 * an atomic move that does <em>not</em> replace an existing file. A reader therefore never sees a
 * partial file, and a second publisher of the same key cannot destroy the committed entry a reader
 * may be using. Nothing here expires, sweeps or redownloads an entry.
 *
 * <p>Thread-safe: it holds no mutable state, and concurrent publication is resolved by the filesystem.
 */
public final class FilesystemIconStore implements IconStore {

    /**
     * The versioned subdirectory the keyed entries live in. Separate from the legacy
     * {@code items/{itemId}.png} files, which stay usable for JavaFX (§12.1).
     */
    public static final String ASSETS_DIRECTORY = "assets-v1";

    private static final String TEMPORARY_SUFFIX = ".tmp";

    private final Path assetsRoot;

    /**
     * @param iconCacheDir the configured {@code ICON_CACHE_DIR} root; the entries live in its
     *                     {@value #ASSETS_DIRECTORY} subdirectory
     */
    public FilesystemIconStore(Path iconCacheDir) {
        this.assetsRoot = iconCacheDir.toAbsolutePath().normalize().resolve(ASSETS_DIRECTORY);
    }

    /** The directory the keyed entries live in; diagnostics and the desktop path update use it. */
    public Path assetsRoot() {
        return assetsRoot;
    }

    @Override
    public Optional<StoredIcon> find(String sourceKey, String extension) throws IconStorageException {
        Path file = confinedEntry(sourceKey, extension);

        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();

            byte[] bytes = Files.readAllBytes(file);

            // A partial, empty, oversized or corrupt file is a miss, not a half-served hit: leaving it
            // in place keeps it diagnosable, and the miss path can repair it on demand.
            if (bytes.length == 0 || bytes.length > IconCacheBounds.MAX_RESPONSE_BYTES) return Optional.empty();
            if (!IconImageBytes.hasSignatureOf(bytes, extension)) return Optional.empty();

            return Optional.of(new StoredIcon(file, bytes));
        } catch (IOException unreadable) {
            throw new IconStorageException("icon cache entry could not be read", unreadable);
        }
    }

    @Override
    public StoredIcon publish(String sourceKey, String extension, byte[] bytes) throws IconStorageException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("refusing to publish empty icon bytes");
        }

        Path destination = confinedEntry(sourceKey, extension);
        Path temporary = destination.resolveSibling(
                destination.getFileName() + "." + UUID.randomUUID() + TEMPORARY_SUFFIX);

        // A plain rename, deliberately not REPLACE_EXISTING and deliberately not ATOMIC_MOVE: a rename
        // within one directory is what publishes the bytes indivisibly for readers, while refusing to
        // touch an already committed entry. (ATOMIC_MOVE would be worse here - on Windows it replaces
        // the destination, which is exactly what a committed entry must be protected from.)

        try {
            Files.createDirectories(assetsRoot);
            Files.write(temporary, bytes);

            try {
                Files.move(temporary, destination);
                return new StoredIcon(destination, bytes);
            } catch (FileAlreadyExistsException committedByAnotherWriter) {
                return alreadyCommitted(sourceKey, extension);
            }
        } catch (IOException unwritable) {
            throw new IconStorageException("icon cache entry could not be committed", unwritable);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private StoredIcon alreadyCommitted(String sourceKey, String extension) throws IconStorageException {
        return find(sourceKey, extension).orElseThrow(() -> new IconStorageException(
                "the icon cache path is occupied by something that is not a usable entry"));
    }

    /**
     * The entry's path, proven to stay inside the configured root. The key/extension shape is checked
     * first: no caller-supplied path, host or name reaches the filesystem, so traversal has nothing to
     * work with, and this check is what keeps that true if the policy ever widens.
     */
    private Path confinedEntry(String sourceKey, String extension) {
        if (!IconSourcePolicy.isSourceKey(sourceKey)) {
            throw new IllegalArgumentException("not a source key: " + sourceKey);
        }
        if (!IconSourcePolicy.isExtension(extension)) {
            throw new IllegalArgumentException("not an accepted icon extension: " + extension);
        }

        Path entry = assetsRoot.resolve(sourceKey + "." + extension).normalize();
        if (!entry.startsWith(assetsRoot)) {
            throw new IllegalArgumentException("icon cache entry resolved outside the configured root");
        }
        return entry;
    }

    private static void deleteQuietly(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException leftBehind) {
            // An abandoned temporary file is harmless - it is never a cache entry - and cleanup may
            // remove it later (§12.1). Failing the publication because of it would be worse.
        }
    }
}
