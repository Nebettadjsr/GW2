package support.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixtureProvenanceTest {

    @Test
    void absentProvenanceSaysAbsentAndIsNeverComparable() {
        ObjectNode absent = FixtureProvenance.absent("none");
        assertEquals("absent", absent.get("state").asText());
        assertTrue(absent.get("comparison").asText().startsWith("noncomparable"));
        assertFalse(FixtureProvenance.comparable(absent, absent.deepCopy()));
    }

    @Test
    void syntheticProvenanceCarriesVersionTopologyCoverageAndIdentities() {
        FixtureSnapshot s = SyntheticFixtures.build();
        JsonNode p = FixtureProvenance.of(s);
        assertEquals("synthetic-populated", p.get("fixtureKind").asText());
        assertEquals(SyntheticFixtures.VERSION, p.get("fixtureVersion").asText());
        assertEquals(s.identity(), p.get("fixtureIdentity").asText());
        assertEquals(s.expectedIdentity(), p.get("expectedResultIdentity").asText());
        assertEquals("synthetic", p.get("topology").get("kind").asText());
        assertEquals("unavailable", p.get("topology").get("publicOnlyCapture").asText());
        assertEquals(5, p.get("coverage").get("accounts").asInt());
        assertEquals("not-qualified", p.get("oracleQualification").asText());
        assertTrue(FixtureProvenance.markdown(FixtureProvenance.of(s)).contains("Topology: synthetic"));
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
