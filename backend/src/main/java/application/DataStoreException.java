package application;

/**
 * The database could not be read for an application use case that only passes the data store
 * through. The cause is the original database exception (a {@code SQLException}); callers present
 * it as "data store unavailable" without learning anything about JDBC.
 */
public class DataStoreException extends Exception {
    public DataStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    /** A repository read whose only checked failure is the database exception. */
    @FunctionalInterface
    public interface Read<T> {
        T get() throws Exception;
    }

    /**
     * Runs {@code read}: unchecked failures pass through unchanged, a checked failure (the
     * repositories declare only the database exception) becomes a {@link DataStoreException} with
     * the original as cause.
     */
    public static <T> T translate(Read<T> read) throws DataStoreException {
        try {
            return read.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new DataStoreException(e.getMessage(), e);
        }
    }
}
