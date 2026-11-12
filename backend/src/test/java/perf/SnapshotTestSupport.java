package perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/** Builds a privacy-safe stand-in for the maintainer's local snapshot, with recognizable "private" markers. */
final class SnapshotTestSupport {
    static final String REAL_ACCOUNT = "deadbeef-dead-4ead-8ead-deadbeef0001";
    static final String REAL_OTHER_ACCOUNT = "deadbeef-dead-4ead-8ead-deadbeef0002";
    static final String REAL_NAME = "Zxqprivatehero";
    static final String REAL_OTHER_NAME = "Qwvsecretalt";
    static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    static final String[] MARKERS = {REAL_ACCOUNT, REAL_OTHER_ACCOUNT, REAL_NAME, REAL_OTHER_NAME, "deadbeef"};
    private static final ObjectMapper M = new ObjectMapper();

    private SnapshotTestSupport() {}

    /** Writes a manifest + jsonl directory derived from the synthetic fixtures with real-looking identities. */
    static void write(Path dir, UnaryOperator<String> perFileTransform) throws IOException {
        write(dir, perFileTransform, java.util.Set.of());
    }

    /** As above, but the named tables are captured empty (coherent zero count and hash). */
    static void write(Path dir, UnaryOperator<String> perFileTransform, java.util.Set<String> emptyTables) throws IOException {
        FixtureSnapshot base = SyntheticFixtures.build();
        ObjectNode manifest = M.createObjectNode();
        manifest.put("format", SnapshotImporter.FORMAT);
        manifest.put("privateLocalOnly", true);
        manifest.put("sourceRevision", REVISION);
        manifest.put("capturedAt", "2026-11-12T01:02:03Z");
        manifest.put("isolation", "read-only-repeatable-read");
        ArrayNode tables = manifest.putArray("tables");
        for (String name : FixtureSnapshot.SHARED_TABLES) writeTable(dir, name, "shared", base, tables, perFileTransform, emptyTables);
        for (String name : FixtureSnapshot.SCOPED_TABLES) writeTable(dir, name, "account", base, tables, perFileTransform, emptyTables);
        Files.writeString(dir.resolve("manifest.json"), manifest.toString());
    }

    private static void writeTable(Path dir, String name, String scope, FixtureSnapshot base, ArrayNode tables,
                                   UnaryOperator<String> transform, java.util.Set<String> emptyTables) throws IOException {
        StringBuilder sb = new StringBuilder();
        java.util.List<ObjectNode> rows = emptyTables.contains(name) ? java.util.List.of() : base.rows(name);
        for (ObjectNode r : rows) {
            String line = r.toString()
                    .replace(SyntheticFixtures.A, REAL_ACCOUNT).replace(SyntheticFixtures.B, REAL_OTHER_ACCOUNT)
                    .replace("\"Aria\"", "\"" + REAL_NAME + "\"").replace("\"Cato\"", "\"" + REAL_OTHER_NAME + "\"");
            if (name.equals("account_bank") && line.contains("\"slot\":0") && line.contains(REAL_ACCOUNT)) {
                line = line.replace("\"item_id\"", "\"bound_to\":\"" + REAL_NAME + "\",\"item_id\"");
            }
            sb.append(line).append('\n');
        }
        byte[] bytes = transform.apply(sb.toString()).getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve(name + ".jsonl"), bytes);
        ObjectNode t = tables.addObject();
        t.put("name", name).put("scope", scope).put("rows", rows.size()).put("sha256", FixtureSnapshot.sha256(bytes));
    }
}
