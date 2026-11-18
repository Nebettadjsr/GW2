package infra.icons;

import java.util.Optional;

/**
 * The persistent icon cache, keyed by canonical source. One implementation per storage medium; {@link FilesystemIconStore} is the selected one.
 *
 * <p>Contract for callers (the image endpoint delivers hits and acquires misses through it):
 *
 * <ul>
 *   <li>Only a fully committed, validated image is an entry. A partial, empty or corrupt file is a
 *       miss, never a half-served hit.</li>
 *   <li>{@link #publish} publishes atomically (callers validate the bytes first, see {@link IconImageBytes}), so a reader never observes a partial
 *       file, and it never overwrites an entry that is already committed.</li>
 *   <li>A hit needs no upstream request and no live metadata.</li>
 * </ul>
 *
 * <p>Implementations must be safe for concurrent use.
 */
public interface IconStore {

    /**
     * The committed entry for this key, or empty when there is none to serve.
     *
     * @param sourceKey source key as {@link IconSourcePolicy} derives it
     * @param extension accepted lowercase extension
     * @throws IllegalArgumentException if either value is not of the policy's shape
     * @throws IconStorageException     if the storage itself could not be consulted
     */
    Optional<StoredIcon> find(String sourceKey, String extension) throws IconStorageException;

    /**
     * Publishes {@code bytes} for this key and returns the committed entry.
     *
     * <p>If another writer committed the same key first, that entry is kept and returned - the caller
     * cannot tell which writer won, which is what makes concurrent publication safe.
     *
     * @throws IllegalArgumentException if the key/extension shape is wrong or {@code bytes} is empty
     * @throws IconStorageException     if the entry could not be committed
     */
    StoredIcon publish(String sourceKey, String extension, byte[] bytes) throws IconStorageException;
}
