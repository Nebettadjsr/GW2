package support.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Safe, aggregate fixture/topology/provenance identity for fixture readiness checks. It holds
 * revision, version/kind, checksums, row counts and states only: never a GUID, name, key or path.
 * Local results are always "not-qualified" for Oracle; reports built from different fixture identities
 * are explicitly not comparable.
 */
public final class FixtureProvenance {

    private FixtureProvenance() {}

    public static ObjectNode absent(String reason) {
        ObjectNode n = new ObjectMapper().createObjectNode();
        n.put("state", "absent");
        n.put("reason", reason);
        n.put("comparison", "noncomparable: no fixture identity recorded");
        n.put("oracleQualification", "not-qualified");
        return n;
    }

    /** Provenance of the pre-existing single-principal scalable generator used by the harness. */
    public static ObjectNode scalableGenerator(String fixtureVersion) {
        ObjectNode n = absent("single-principal scalable generator; no populated account fixture was loaded");
        n.put("state", "available");
        n.put("fixtureKind", "scalable-generator");
        n.put("fixtureVersion", fixtureVersion);
        n.put("comparison", "comparable only with the same fixtureVersion and the same kind");
        topology(n, "synthetic", "no public topology is captured or invented");
        return n;
    }

    public static ObjectNode of(FixtureSnapshot s) {
        ObjectNode n = new ObjectMapper().createObjectNode();
        boolean synthetic = s.kind() == FixtureSnapshot.Kind.SYNTHETIC;
        n.put("state", "available");
        n.put("fixtureKind", synthetic ? "synthetic-populated" : "local-snapshot");
        n.put("fixtureVersion", s.version());
        n.put("importVersion", SnapshotImporter.IMPORT_VERSION);
        n.put("fixtureIdentity", s.identity());
        n.put("expectedResultIdentity", s.expectedIdentity());
        n.put("sourceRevision", s.sourceRevision() == null ? "unavailable" : s.sourceRevision());
        n.put("clockTransformation", s.clockTransformation() == null ? "none" : s.clockTransformation());
        topology(n, synthetic ? "synthetic" : "locally-captured-cache",
                synthetic ? "synthetic catalog and prices; no public topology is captured or invented"
                        : "stored cache facts as captured at snapshot time (not raw upstream responses); fetch age may be stale and public-only coverage is unavailable unless captured");
        ObjectNode coverage = n.putObject("coverage");
        coverage.put("accounts", s.rows("account_registry").size());
        ObjectNode tables = coverage.putObject("tableRows");
        for (Map.Entry<String, Integer> e : s.tableCounts().entrySet()) tables.put(e.getKey(), e.getValue());
        n.put("comparison", synthetic
                ? "comparable only with the same fixtureVersion, fixtureIdentity and expectedResultIdentity"
                : "noncomparable: local snapshot results are private and comparable only with the same fixtureIdentity on the same machine");
        n.put("oracleQualification", "not-qualified");
        return n;
    }

    private static void topology(ObjectNode n, String kind, String limits) {
        ObjectNode t = n.putObject("topology");
        t.put("kind", kind);
        t.put("limitations", limits);
        t.put("publicOnlyCapture", "unavailable");
    }

    /**
     * Two provenance nodes are comparable only when both are available and identical in kind and version,
     * and, for populated fixtures, in fixture and expected-result identity. The scalable generator has no
     * per-run identities: it is versioned by {@code fixtureVersion} alone. A local snapshot is never comparable.
     */
    public static boolean comparable(ObjectNode a, ObjectNode b) {
        if (!"available".equals(a.path("state").asText()) || !"available".equals(b.path("state").asText())) return false;
        String kind = a.path("fixtureKind").asText();
        if (!kind.equals(b.path("fixtureKind").asText())) return false;
        if (!a.path("fixtureVersion").asText("a").equals(b.path("fixtureVersion").asText("b"))) return false;
        if (kind.equals("scalable-generator")) return true;
        if (!kind.equals("synthetic-populated")) return false;
        for (String f : new String[] {"fixtureIdentity", "expectedResultIdentity"}) {
            if (!a.hasNonNull(f) || !b.hasNonNull(f) || !a.get(f).asText().equals(b.get(f).asText())) return false;
        }
        return true;
    }

    public static String markdown(ObjectNode p) {
        StringBuilder sb = new StringBuilder("- Fixture provenance: ").append(p.path("state").asText());
        if ("absent".equals(p.path("state").asText())) return sb.append(" (").append(p.path("reason").asText()).append(")\n").toString();
        sb.append("; kind `").append(p.path("fixtureKind").asText()).append("`, version `").append(p.path("fixtureVersion").asText()).append('`');
        if (p.has("fixtureIdentity")) sb.append(", identity `").append(p.path("fixtureIdentity").asText()).append('`');
        sb.append("\n- Topology: ").append(p.path("topology").path("kind").asText()).append(" — ").append(p.path("topology").path("limitations").asText());
        sb.append("\n- Comparison: ").append(p.path("comparison").asText()).append("; Oracle qualification: ").append(p.path("oracleQualification").asText()).append('\n');
        return sb.toString();
    }
}
