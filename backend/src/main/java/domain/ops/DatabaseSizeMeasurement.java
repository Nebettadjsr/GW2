package domain.ops;

/** Narrow port: {@code SELECT pg_database_size(current_database())}. */
public interface DatabaseSizeMeasurement {
    long totalBytes() throws Exception;
}
