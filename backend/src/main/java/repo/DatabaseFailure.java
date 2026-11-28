package repo;

import java.sql.SQLException;

/** Recognizes a database failure without making the caller depend on JDBC types. */
public final class DatabaseFailure {
    private DatabaseFailure() { }

    public static boolean isDatabaseFailure(Throwable failure) {
        return failure instanceof SQLException;
    }
}
