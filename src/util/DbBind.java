package util;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

public final class DbBind {
    private DbBind() {}

    public static void setIntOrNull(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER);
        else ps.setInt(idx, v);
    }

    public static void setLongOrNull(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.BIGINT);
        else ps.setLong(idx, v);
    }

    public static void setStringOrNull(PreparedStatement ps, int idx, String v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, v);
    }
}