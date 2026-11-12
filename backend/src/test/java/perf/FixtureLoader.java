package perf;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes a {@link FixtureSnapshot} into a newly created, disposable qualification schema that already
 * went through the production Flyway chain. It refuses {@code public} and any schema not named
 * {@code perf_*} / {@code kpi_*}, and never opens a connection of its own. Rows are inserted with
 * {@code jsonb_populate_record}, so the real column types and constraints validate every row.
 */
public final class FixtureLoader {

    private static final Pattern DISPOSABLE = Pattern.compile("(perf|kpi)_[a-z0-9_]{1,50}");

    public static final class LoadException extends Exception {
        public LoadException(String message) { super(message); }
    }

    private FixtureLoader() {}

    public static void load(Connection c, String schema, FixtureSnapshot snapshot) throws Exception {
        if (schema == null || !DISPOSABLE.matcher(schema).matches()) {
            throw new LoadException("refusing to load into a schema that is not a disposable perf_/kpi_ schema");
        }
        c.setSchema(schema);
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            requireEmpty(c, schema);
            for (String table : concat(FixtureSnapshot.SHARED_TABLES, FixtureSnapshot.SCOPED_TABLES)) {
                List<ObjectNode> rows = snapshot.rows(table);
                if (rows.isEmpty()) continue;
                Set<String> columns = columns(c, schema, table);
                for (ObjectNode row : rows) {
                    for (Iterator<String> it = row.fieldNames(); it.hasNext(); ) {
                        String f = it.next();
                        if (!columns.contains(f)) throw new LoadException("table " + table + " has column " + f + " that this schema does not support");
                    }
                }
                // The table name comes from the fixed allowlist above, never from input.
                String sql = "INSERT INTO " + table + " SELECT * FROM jsonb_populate_record(NULL::" + table + ", ?::jsonb)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (ObjectNode row : rows) {
                        ps.setString(1, row.toString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                } catch (java.sql.SQLException e) {
                    throw new LoadException("table " + table + " violates the current schema (" + e.getSQLState() + ")");
                }
            }
            c.commit();
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static void requireEmpty(Connection c, String schema) throws Exception {
        for (String t : List.of("items", "account_registry")) {
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + t); ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) != 0) throw new LoadException("target schema is not newly created: " + t + " already has rows");
            }
        }
    }

    private static Set<String> columns(Connection c, String schema, String table) throws Exception {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        if (out.isEmpty()) throw new LoadException("table " + table + " does not exist in the current schema");
        return out;
    }
}
