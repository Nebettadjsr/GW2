package perf;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotImporterTest {

    @TempDir Path tmp;

    private Path snapshot(UnaryOperator<String> t) throws Exception {
        Path d = Files.createDirectory(tmp.resolve("s" + System.nanoTime()));
        SnapshotTestSupport.write(d, t);
        return d;
    }

    private static void assertNoMarker(String text) {
        for (String m : SnapshotTestSupport.MARKERS) assertFalse(text.contains(m), "leaked marker in output");
    }

    private String fail(Path dir) {
        return assertThrows(SnapshotImporter.ImportException.class, () -> SnapshotImporter.read(dir)).getMessage();
    }

    @Test
    void importsStandInSnapshotAndRemapsEveryIdentityIncludingDependentRows() throws Exception {
        FixtureSnapshot s = SnapshotImporter.read(snapshot(UnaryOperator.identity()));
        assertEquals(FixtureSnapshot.Kind.LOCAL_SNAPSHOT, s.kind());
        String all = s.tables().toString() + s.version() + s.sourceRevision();
        assertNoMarker(all.replace(SnapshotTestSupport.REVISION, ""));
        assertEquals(5, s.rows("account_registry").size());
        for (String table : FixtureSnapshot.SCOPED_TABLES) {
            for (ObjectNode r : s.rows(table)) assertFalse(r.get("account_id").asText().startsWith("deadbeef"), table);
        }
        // Dependent character rows follow the remapped character identity and all stay inside their account.
        var chars = s.rows("characters");
        assertTrue(chars.stream().allMatch(c -> c.get("name").asText().startsWith("Fake Character ")));
        var bank = s.rows("account_bank").stream().filter(r -> r.hasNonNull("bound_to")).toList();
        assertEquals(1, bank.size());
        assertTrue(bank.get(0).get("bound_to").asText().startsWith("Fake Character "), "bound_to follows the character remap");
        assertEquals(s.identity(), SnapshotImporter.read(snapshot(UnaryOperator.identity())).identity(), "remap is deterministic");
        // Overlapping real character IDs stay separate per fake account.
        assertEquals(2, s.rows("characters").stream().filter(c -> c.get("character_id").asInt() == 1).count() >= 2 ? 2 : 0);
    }

    @Test
    void reportProvenanceCarriesOnlySafeAggregates() throws Exception {
        FixtureSnapshot s = SnapshotImporter.read(snapshot(UnaryOperator.identity()));
        ObjectNode p = FixtureProvenance.of(s);
        assertNoMarker(p.toString() + FixtureProvenance.markdown(p));
        assertEquals("local-snapshot", p.get("fixtureKind").asText());
        assertEquals("locally-captured-cache", p.get("topology").get("kind").asText());
        assertEquals("not-qualified", p.get("oracleQualification").asText());
        assertTrue(p.get("comparison").asText().startsWith("noncomparable"));
        assertEquals("unavailable", p.get("expectedResultIdentity").asText());
        assertFalse(FixtureProvenance.comparable(p, p), "local snapshots are never comparable");
    }

    @Test
    void absentOptInIsUnavailableNotFabricated() throws Exception {
        System.clearProperty(SnapshotImporter.PROPERTY);
        if (System.getenv(SnapshotImporter.ENV) != null) return; // a maintainer machine may opt in via the environment
        SnapshotImporter.Result r = SnapshotImporter.fromConfiguration();
        assertFalse(r.available());
        assertTrue(r.unavailableReason().contains("no local snapshot configured"));
    }

    @Test
    void rejectsCorruptUnsupportedAndIncompleteSources() throws Exception {
        Path d = snapshot(UnaryOperator.identity());
        Files.writeString(d.resolve("items.jsonl"), Files.readString(d.resolve("items.jsonl")).replace("Fixture", "Tampered"));
        assertTrue(fail(d).contains("sha256 does not match"));

        d = snapshot(UnaryOperator.identity());
        Files.writeString(d.resolve("manifest.json"), Files.readString(d.resolve("manifest.json")).replace(SnapshotImporter.FORMAT, "local-postgres-jsonl-2"));
        assertTrue(fail(d).contains("unsupported snapshot format"));

        d = snapshot(UnaryOperator.identity());
        Files.writeString(d.resolve("manifest.json"), Files.readString(d.resolve("manifest.json")).replace("\"privateLocalOnly\":true", "\"privateLocalOnly\":false"));
        assertTrue(fail(d).contains("privateLocalOnly"));

        d = snapshot(UnaryOperator.identity());
        Files.writeString(d.resolve("manifest.json"), Files.readString(d.resolve("manifest.json")).replace(SnapshotTestSupport.REVISION, "nothex"));
        assertTrue(fail(d).contains("sourceRevision"));

        d = snapshot(UnaryOperator.identity());
        Files.writeString(d.resolve("manifest.json"), Files.readString(d.resolve("manifest.json")).replace("read-only-repeatable-read", "read-committed"));
        assertTrue(fail(d).contains("isolation"));

        d = snapshot(UnaryOperator.identity());
        Files.delete(d.resolve("tp_prices.jsonl"));
        assertTrue(fail(d).contains("tp_prices.jsonl"));

        d = snapshot(UnaryOperator.identity());
        Files.writeString(d.resolve("extra.jsonl"), "{}\n");
        assertTrue(fail(d).contains("unlisted file"));

        d = snapshot(UnaryOperator.identity());
        Files.writeString(d.resolve("manifest.json"), Files.readString(d.resolve("manifest.json")).replace("\"name\":\"items\"", "\"name\":\"../items\""));
        assertTrue(fail(d).contains("outside the allowlist"));

        d = snapshot(UnaryOperator.identity());
        Files.writeString(d.resolve("manifest.json"), Files.readString(d.resolve("manifest.json")).replace("\"rows\":8", "\"rows\":9"));
        assertTrue(fail(d).contains("row count"));

        d = snapshot(UnaryOperator.identity());
        Files.writeString(d.resolve("manifest.json"), "{not json");
        assertTrue(fail(d).contains("not valid JSON"));
    }

    @Test
    void rejectsBrokenSchemaAndPublicationRelationshipsWithoutLeakingValues() throws Exception {
        Path orphan = snapshot(s -> s.replace("\"recipe_id\":20002,\"item_id\":3", "\"recipe_id\":99999,\"item_id\":3"));
        String msg = fail(orphan);
        assertTrue(msg.contains("recipe_ingredients references a recipe"), msg);
        assertNoMarker(msg);

        Path noPublication = snapshot(s -> s.contains("\"singleton\"") ? "" : s);
        // Rewriting the file invalidates the declared count first; the error must still be explicit and safe.
        assertNoMarker(fail(noPublication));

        Path badUtf8 = Files.createDirectory(tmp.resolve("utf8"));
        SnapshotTestSupport.write(badUtf8, UnaryOperator.identity());
        byte[] bytes = {(byte) 0xC3, (byte) 0x28, '\n'};
        Files.write(badUtf8.resolve("build_pets.jsonl"), bytes);
        String m = Files.readString(badUtf8.resolve("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(fail(badUtf8).contains("sha256"), "corrupt bytes are rejected before parsing");
        assertNotNull(m);
    }

    @Test
    void foreignAccountReferenceInADependentTableIsRejected() throws Exception {
        Path d = snapshot(s -> s.replaceAll("\"account_id\":\"" + SnapshotTestSupport.REAL_ACCOUNT + "\",\"character_id\":2", "\"account_id\":\"deadbeef-dead-4ead-8ead-deadbeef0999\",\"character_id\":2"));
        String msg = fail(d);
        assertTrue(msg.contains("references an account that is not in account_registry"), msg);
        assertNoMarker(msg);
    }

    @Test
    void explicitClockShiftIsUniformReportedAndDoesNotMutateTheSource() throws Exception {
        FixtureSnapshot s = SnapshotImporter.read(snapshot(UnaryOperator.identity()));
        FixtureSnapshot shifted = SnapshotImporter.shiftClock(s, OffsetDateTime.parse("2030-01-01T00:00:00Z"));
        assertNotNull(shifted.clockTransformation());
        assertNull(s.clockTransformation());
        assertTrue(shifted.rows("account_source_state").stream().anyMatch(r -> r.get("completed_at").asText().startsWith("2030-01-01")));
        assertTrue(FixtureProvenance.of(shifted).get("clockTransformation").asText().contains("fixture preparation"));
        assertEquals(s.rows("account_bank").size(), shifted.rows("account_bank").size());
    }
}
