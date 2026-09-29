package repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.HashMap;

/** Applies and verifies the small account Luck schema before Luck reads or writes are served. */
public final class AccountLuckSchema {
    private static final String RESOURCE = "/db/manual/account_luck.sql";

    private AccountLuckSchema() {}

    public static void ensure() throws SQLException {
        try (Connection con = Db.open()) {
            ensure(con);
        }
    }

    public static void ensure(Connection con) throws SQLException {
        String ddl;
        try (var stream = AccountLuckSchema.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new SQLException("Missing account Luck schema resource: " + RESOURCE);
            ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("Cannot read account Luck schema resource", e);
        }
        try (Statement statement = con.createStatement()) {
            statement.execute(ddl);
        }

        Map<String, String> columns = new HashMap<>();
        try (PreparedStatement statement = con.prepareStatement("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'account_luck'
                """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) columns.put(rows.getString(1), rows.getString(2) + ":" + rows.getString(3));
        }
        if (!"text:NO".equals(columns.get("account_id"))
                || !"bigint:NO".equals(columns.get("consumed_luck"))
                || !"timestamp with time zone:NO".equals(columns.get("fetched_at"))) {
            throw new SQLException("account_luck schema is incompatible with the Account Luck repository");
        }
    }
}
