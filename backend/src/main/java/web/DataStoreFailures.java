package web;

import application.DataStoreException;
import application.ScopedSourceGate;

import java.sql.SQLException;

/**
 * Bridges an application read that reports {@link DataStoreException} into
 * {@link ScopedSourceGate.Rows}, whose contract still declares the database exception: the original
 * exception is handed on unchanged, so the existing handlers keep producing the same answer.
 */
final class DataStoreFailures {
    private DataStoreFailures() { }

    @FunctionalInterface
    interface Read<T> { T get() throws DataStoreException; }

    static <T> T asSql(Read<T> read) throws SQLException {
        try {
            return read.get();
        } catch (DataStoreException e) {
            if (e.getCause() instanceof SQLException sql) throw sql;
            throw new SQLException(e.getMessage(), e);
        }
    }
}
