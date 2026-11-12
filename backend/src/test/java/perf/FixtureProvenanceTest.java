package perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FixtureProvenanceTest {

    @Test
    void reportWithoutProvenanceStaysCompatibleAndSaysAbsent() {
        MeasurementReport r = new MeasurementReport("c", Instant.EPOCH, "f", Map.of());
        JsonNode json = r.toJson();
        assertEquals("1", json.get("schemaVersion").asText());
        assertEquals("absent", json.get("fixtureProvenance").get("state").asText());
        assertTrue(json.get("fixtureProvenance").get("comparison").asText().startsWith("noncomparable"));
        assertTrue(r.toMarkdown().contains("Fixture provenance: absent"));
    }

    @Test
    void syntheticProvenanceCarriesVersionTopologyCoverageAndIdentities() {
        FixtureSnapshot s = SyntheticFixtures.build();
        MeasurementReport r = new MeasurementReport("c", Instant.EPOCH, "f", Map.of());
        r.setFixtureProvenance(FixtureProvenance.of(s));
        JsonNode p = r.toJson().get("fixtureProvenance");
        assertEquals("synthetic-populated", p.get("fixtureKind").asText());
        assertEquals(SyntheticFixtures.VERSION, p.get("fixtureVersion").asText());
        assertEquals(s.identity(), p.get("fixtureIdentity").asText());
        assertEquals(s.expectedIdentity(), p.get("expectedResultIdentity").asText());
        assertEquals("synthetic", p.get("topology").get("kind").asText());
        assertEquals("unavailable", p.get("topology").get("publicOnlyCapture").asText());
        assertEquals(5, p.get("coverage").get("accounts").asInt());
        assertEquals("not-qualified", p.get("oracleQualification").asText());
        assertTrue(r.toMarkdown().contains("Topology: synthetic"));
        assertTrue(FixtureProvenance.comparable(FixtureProvenance.of(s), FixtureProvenance.of(SyntheticFixtures.build())));
    }

    @Test
    void differentOrAbsentIdentitiesAreExplicitlyIncomparable() {
        ObjectNode synthetic = FixtureProvenance.of(SyntheticFixtures.build());
        ObjectNode scalable = FixtureProvenance.scalableGenerator("perf-fixtures-2");
        assertFalse(FixtureProvenance.comparable(synthetic, scalable));
        assertFalse(FixtureProvenance.comparable(synthetic, FixtureProvenance.absent("none")));
        ObjectNode changed = synthetic.deepCopy();
        changed.put("fixtureIdentity", "other");
        assertFalse(FixtureProvenance.comparable(synthetic, changed));
    }

    @Test
    void scalableGeneratorReportsCompareByVersionAndKind() {
        ObjectNode a = FixtureProvenance.scalableGenerator("perf-fixtures-2");
        assertTrue(FixtureProvenance.comparable(a, FixtureProvenance.scalableGenerator("perf-fixtures-2")));
        assertFalse(FixtureProvenance.comparable(a, FixtureProvenance.scalableGenerator("perf-fixtures-3")));
        ObjectNode local = FixtureProvenance.of(SyntheticFixtures.build());
        local.put("fixtureKind", "local-snapshot");
        assertFalse(FixtureProvenance.comparable(local, local.deepCopy()), "local snapshots are never comparable");
    }
}
