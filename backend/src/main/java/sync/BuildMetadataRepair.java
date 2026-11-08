package sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.AccountScope;
import model.BuildResourceType;
import repo.BuildMetadataRepository;
import repo.BuildMetadataRepository.*;
import repo.Db;
import parser.BuildDisplayDetails;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * Bounded repair of the shared build-reference metadata (STORY-CHAR-009). Reads the account's already
 * stored references, fetches only the namespace-batched ids that are missing (zero requests when warm),
 * and upserts them. Never part of an account publication transaction: every database step opens and
 * closes its own connection, so none is held during HTTP. Any ordinary failure of one namespace is
 * logged and skipped (prior rows stay intact, refs stay unresolved); only interruption propagates.
 */
public final class BuildMetadataRepair {

    static final int BATCH = 200;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Replaceable upstream edge. Returns the JSON array for the public endpoint; empty when nothing valid. */
    public interface Source {
        JsonNode fetch(String endpoint, List<String> ids) throws Exception;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /** Live adapter with finite connect and request timeouts. 404 means none of the ids exist. */
    public static JsonNode fetchLive(String endpoint, List<String> ids) throws Exception {
        String url = "https://api.guildwars2.com/v2/" + endpoint + "?ids=" + String.join(",", ids);
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        int status = res.statusCode();
        if (status == 404) return MAPPER.createArrayNode();
        if (status != 200 && status != 206) throw new IOException("GET " + endpoint + " failed: HTTP " + status);
        JsonNode body = MAPPER.readTree(res.body());
        if (body == null || !body.isArray()) throw new IOException("GET " + endpoint + " returned a non-array body");
        return body;
    }

    private final Source source;
    private final Clock clock;
    private final BuildMetadataRepository repo = new BuildMetadataRepository();

    public BuildMetadataRepair(Source source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    /** Repairs the metadata of every reference stored for the account; nonfatal except interruption. */
    public void repairStored(AccountScope scope) throws InterruptedException {
        Refs refs;
        try (Connection con = Db.open()) {
            refs = repo.storedRefs(con, scope);
        } catch (Exception unavailable) {
            System.out.println("Build metadata references unavailable: " + unavailable.getClass().getSimpleName());
            return;
        }
        repair(refs);
    }

    public void repair(Refs refs) throws InterruptedException {
        if (refs.isEmpty()) return;
        step("specializations", () -> specializations(refs.specializations()));
        step("traits", () -> {
            Set<Integer> wanted = new TreeSet<>(refs.traits());
            try (Connection con = Db.open()) { wanted.addAll(repo.traitClosure(con, refs.specializations())); }
            traits(wanted);
        });
        step("legends", () -> legends(refs.legends()));
        step("skills", () -> {
            Set<Integer> wanted = new TreeSet<>(refs.skills());
            try (Connection con = Db.open()) { wanted.addAll(repo.swapSkills(con, refs.legends())); }
            named(BuildResourceType.SKILLS, wanted);
        });
        step("pets", () -> named(BuildResourceType.PETS, refs.pets()));
    }

    private interface Step { void run() throws Exception; }

    private static void step(String name, Step step) throws InterruptedException {
        try {
            step.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception unavailable) {
            System.out.println("Build " + name + " metadata unavailable; left unresolved for a later repair: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private List<Integer> missing(BuildResourceType type, Collection<Integer> ids) throws Exception {
        if (ids.isEmpty()) return List.of();
        try (Connection con = Db.open()) {
            Set<Integer> have = repo.present(con, type, ids);
            List<Integer> out = new ArrayList<>();
            for (Integer id : ids) if (!have.contains(id)) out.add(id);
            return out;
        }
    }

    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += BATCH) out.add(all.subList(i, Math.min(all.size(), i + BATCH)));
        return out;
    }

    private static List<String> strings(List<Integer> ids) {
        return ids.stream().map(String::valueOf).toList();
    }

    private void specializations(Collection<Integer> ids) throws Exception {
        for (List<Integer> batch : chunks(missing(BuildResourceType.SPECIALIZATIONS, ids))) {
            JsonNode root = source.fetch("specializations", strings(batch));
            List<SpecRow> rows = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (JsonNode n : root) {
                Integer id = intOf(n, "id");
                String name = text(n, "name");
                List<Integer> minor = intList(n.get("minor_traits"));
                List<Integer> major = intList(n.get("major_traits"));
                String profession = text(n, "profession");
                if (id == null || !batch.contains(id) || name == null || profession == null || minor == null
                        || major == null || !n.path("elite").isBoolean() || !seen.add(id)) continue;
                rows.add(new SpecRow(id, name, profession, n.get("elite").asBoolean(),
                        text(n, "icon"), minor, major, BuildDisplayDetails.capture(n)));
            }
            try (Connection con = Db.open()) { repo.upsertSpecializations(con, rows, clock.instant()); }
        }
    }

    private void traits(Collection<Integer> ids) throws Exception {
        for (List<Integer> batch : chunks(missing(BuildResourceType.TRAITS, ids))) {
            JsonNode root = source.fetch("traits", strings(batch));
            List<TraitRow> rows = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (JsonNode n : root) {
                Integer id = intOf(n, "id");
                String name = text(n, "name");
                Integer spec = intOf(n, "specialization");
                Integer tier = nonNegative(n, "tier");
                Integer order = nonNegative(n, "order");
                String slot = text(n, "slot");
                // A partial answer is never stored: it would replace a complete row's facts with nulls.
                if (id == null || !batch.contains(id) || name == null || spec == null || tier == null
                        || order == null || slot == null || !seen.add(id)) continue;
                rows.add(new TraitRow(id, name, text(n, "icon"), spec, tier, order, slot, BuildDisplayDetails.capture(n)));
            }
            try (Connection con = Db.open()) { repo.upsertTraits(con, rows, clock.instant()); }
        }
    }

    private void named(BuildResourceType type, Collection<Integer> ids) throws Exception {
        for (List<Integer> batch : chunks(missing(type, ids))) {
            JsonNode root = source.fetch(type.segment(), strings(batch));
            List<NamedRow> rows = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (JsonNode n : root) {
                Integer id = intOf(n, "id");
                String name = text(n, "name");
                if (id == null || !batch.contains(id) || name == null || !seen.add(id)) continue;
                rows.add(new NamedRow(id, name, text(n, "icon"), BuildDisplayDetails.capture(n)));
            }
            try (Connection con = Db.open()) { repo.upsertNamed(con, type, rows, clock.instant()); }
        }
    }

    private void legends(Collection<String> ids) throws Exception {
        List<String> missing;
        if (ids.isEmpty()) return;
        try (Connection con = Db.open()) {
            Set<String> have = repo.presentLegends(con, ids);
            missing = ids.stream().filter(i -> !have.contains(i)).toList();
        }
        for (List<String> batch : chunks(missing)) {
            JsonNode root = source.fetch("legends", batch);
            List<LegendRow> rows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode n : root) {
                String id = text(n, "id");
                Integer swap = intOf(n, "swap");
                if (id == null || swap == null || !batch.contains(id) || !seen.add(id)) continue;
                rows.add(new LegendRow(id, swap));
            }
            try (Connection con = Db.open()) { repo.upsertLegends(con, rows, clock.instant()); }
        }
    }

    private static Integer intOf(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isInt() && v.asInt() > 0 ? v.asInt() : null;
    }

    /** Zero is a documented, valid value (tier 0, order 0). */
    private static Integer nonNegative(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isInt() && v.asInt() >= 0 ? v.asInt() : null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static List<Integer> intList(JsonNode v) {
        if (v == null || !v.isArray()) return null;
        List<Integer> out = new ArrayList<>();
        for (JsonNode e : v) {
            if (!e.isInt() || e.asInt() <= 0) return null;
            out.add(e.asInt());
        }
        return out;
    }
}
