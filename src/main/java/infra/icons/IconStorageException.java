package infra.icons;

/**
 * The cache's storage is unusable for this operation - missing, unwritable, out of space or failing
 * to read. Deliberately distinct from a miss: a miss is repairable by fetching, this is not, and
 * §12.1 requires it to surface as an unavailable response rather than as a successful uncached image.
 */
public class IconStorageException extends Exception {

    public IconStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public IconStorageException(String message) {
        super(message);
    }
}
