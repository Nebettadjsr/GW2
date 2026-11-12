package perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The one optional local snapshot import seam (STORY-KPI-007). It reads a directory in the
 * {@code local-postgres-jsonl-1} shape, validates the manifest, file names, counts, hashes and the
 * cache relationships it depends on, then remaps every real account GUID, character ID and name to an
 * independent fake principal. Nothing is read unless the maintainer opts in
 * ({@value #PROPERTY} or {@value #ENV}); there is no discovery of a database or a default path.
 *
 * <p>Failure messages name tables, files and counts only: never a row value, GUID, name or path.
 */
public final class SnapshotImporter {

    public static final String IMPORT_VERSION = "snapshot-import-1";
    public static final String FORMAT = "local-postgres-jsonl-1";
    public static final String PROPERTY = "gw2.perf.snapshot-dir";
    public static final String ENV = "GW2_PERF_SNAPSHOT_DIR";

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern HEX40 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> TIME_COLUMNS = Set.of("fetched_at", "completed_at", "last_seen_at",
            "last_successful_sync_at", "created_at", "updated_at");
    private static final Set<String> KNOWN_SOURCES = Set.of("BANK", "MATERIALS", "ACCOUNT_RECIPES", "LUCK",
            "CHARACTERS", "CHARACTER_EQUIPMENT_TABS", "CHARACTER_BUILD_TABS");

    /** An unsupported, incomplete or corrupt source. The message is safe to log. */
    public static final class ImportException extends Exception {
        public ImportException(String message) { super(message); }
    }

    /** Either the opted-in snapshot or an explicit unavailable state; never a fabricated capture. */
    public record Result(FixtureSnapshot snapshot, String unavailableReason) {
        public boolean available() { return snapshot != null; }
    }

    private SnapshotImporter() {}

    /** Absent opt-in is "unavailable", not an error and not an empty fixture. */
    public static Result fromConfiguration() throws ImportException {
        String dir = System.getProperty(PROPERTY);
        if (dir == null || dir.isBlank()) dir = System.getenv(ENV);
        if (dir == null || dir.isBlank()) {
            return new Result(null, "no local snapshot configured (opt in with -D" + PROPERTY + "=<dir>)");
        }
        return new Result(read(Path.of(dir)), null);
    }

    public static FixtureSnapshot read(Path dir) throws ImportException {
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new ImportException("snapshot directory is missing or not a plain directory");
        JsonNode manifest = readManifest(dir);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, String> scopes = new LinkedHashMap<>();
        for (JsonNode t : manifest.get("tables")) {
            String name = text(t, "name");
            if (!FixtureSnapshot.ALL_TABLES.contains(name)) throw new ImportException("manifest lists a table outside the allowlist");
            if (counts.containsKey(name)) throw new ImportException("manifest lists table " + name + " twice");
            String scope = text(t, "scope");
            String expectedScope = FixtureSnapshot.SHARED_TABLES.contains(name) ? "shared" : "account";
            if (!expectedScope.equals(scope)) throw new ImportException("table " + name + " has an unexpected scope");
            if (!t.has("rows") || !t.get("rows").canConvertToInt() || t.get("rows").asInt() < 0) throw new ImportException("table " + name + " has an invalid row count");
            String sha = text(t, "sha256");
            if (!HEX64.matcher(sha).matches()) throw new ImportException("table " + name + " has an invalid sha256");
            counts.put(name, t.get("rows").asInt());
            hashes.put(name, sha);
            scopes.put(name, scope);
        }
        for (String required : FixtureSnapshot.ALL_TABLES) {
            if (!counts.containsKey(required)) throw new ImportException("snapshot is incomplete: table " + required + " is not listed");
        }
        checkDirectoryContents(dir, counts.keySet());

        Map<String, List<ObjectNode>> tables = new LinkedHashMap<>();
        for (String name : counts.keySet()) {
            Path file = dir.resolve(name + ".jsonl");
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) throw new ImportException("table file " + name + ".jsonl is missing or not a regular file");
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new ImportException("table file " + name + ".jsonl cannot be read");
            }
            if (!FixtureSnapshot.sha256(bytes).equals(hashes.get(name))) throw new ImportException("table " + name + " sha256 does not match its manifest entry");
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                throw new ImportException("table " + name + " is not valid UTF-8");
            }
            List<ObjectNode> rows = new ArrayList<>();
            int lineNo = 0;
            for (String line : content.split("\n", -1)) {
                lineNo++;
                if (line.isEmpty()) continue;
                try {
                    JsonNode n = M.readTree(line);
                    if (n == null || !n.isObject()) throw new ImportException("table " + name + " line " + lineNo + " is not a JSON object");
                    rows.add((ObjectNode) n);
                } catch (IOException e) {
                    throw new ImportException("table " + name + " line " + lineNo + " is not valid JSON");
                }
            }
            if (rows.size() != counts.get(name)) throw new ImportException("table " + name + " row count does not match its manifest entry");
            tables.put(name, rows);
        }
        validateRelationships(tables);
        Map<String, List<ObjectNode>> remapped = remap(tables);
        String identity = FixtureSnapshot.sha256(String.join(",", hashes.values()) + "|" + IMPORT_VERSION);
        return new FixtureSnapshot(FixtureSnapshot.Kind.LOCAL_SNAPSHOT, IMPORT_VERSION + ":" + identity.substring(0, 16),
                remapped, Map.of(), manifest.get("sourceRevision").asText(), null);
    }

    private static JsonNode readManifest(Path dir) throws ImportException {
        Path file = dir.resolve("manifest.json");
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) throw new ImportException("manifest.json is missing");
        JsonNode m;
        try {
            m = M.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new ImportException("manifest.json is not valid JSON");
        }
        if (m == null || !m.isObject()) throw new ImportException("manifest.json is not an object");
        if (!FORMAT.equals(text(m, "format"))) throw new ImportException("unsupported snapshot format version");
        if (!m.path("privateLocalOnly").isBoolean() || !m.get("privateLocalOnly").asBoolean()) throw new ImportException("manifest must declare privateLocalOnly=true");
        if (!HEX40.matcher(text(m, "sourceRevision")).matches()) throw new ImportException("manifest sourceRevision is not a 40-hex commit");
        try {
            java.time.Instant.parse(text(m, "capturedAt"));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ImportException("manifest capturedAt is not an ISO instant");
        }
        if (!"read-only-repeatable-read".equals(text(m, "isolation"))) throw new ImportException("unsupported snapshot isolation");
        if (!m.path("tables").isArray()) throw new ImportException("manifest tables must be a list");
        return m;
    }

    private static void checkDirectoryContents(Path dir, Set<String> listed) throws ImportException {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                boolean ok = n.equals("manifest.json") || (n.endsWith(".jsonl") && listed.contains(n.substring(0, n.length() - 6)));
                if (!ok) throw new ImportException("snapshot directory contains an unlisted file");
            }
        } catch (IOException e) {
            throw new ImportException("snapshot directory cannot be listed");
        }
    }

    private static String text(JsonNode n, String field) throws ImportException {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) throw new ImportException("manifest field " + field + " is missing or not text");
        return v.asText();
    }

    // ------------------------------------------------------------------ relationship validation

    private static Set<String> keys(List<ObjectNode> rows, String field) {
        Set<String> out = new HashSet<>();
        for (ObjectNode r : rows) out.add(r.path(field).asText());
        return out;
    }

    static void validateRelationships(Map<String, List<ObjectNode>> t) throws ImportException {
        if (!t.get("recipes").isEmpty()) {
            List<ObjectNode> pub = t.get("recipe_catalog_publication");
            if (pub.size() != 1 || !pub.get(0).path("singleton").asBoolean(false)) throw new ImportException("recipes exist without exactly one recipe_catalog_publication row");
        }
        Set<String> recipeIds = keys(t.get("recipes"), "recipe_id");
        Set<String> itemIds = keys(t.get("items"), "item_id");
        for (ObjectNode r : t.get("recipe_ingredients")) {
            if (!recipeIds.contains(r.path("recipe_id").asText())) throw new ImportException("recipe_ingredients references a recipe that is not in recipes");
            if (!itemIds.contains(r.path("item_id").asText())) throw new ImportException("recipe_ingredients references an item that is not in items");
        }
        Set<String> categories = keys(t.get("material_categories"), "category_id");
        for (ObjectNode r : t.get("material_category_items")) {
            if (!categories.contains(r.path("category_id").asText())) throw new ImportException("material_category_items references an unknown category");
        }
        Set<String> accounts = keys(t.get("account_registry"), "account_id");
        if (accounts.size() != t.get("account_registry").size()) throw new ImportException("account_registry has duplicate accounts");
        for (String table : FixtureSnapshot.SCOPED_TABLES) {
            for (ObjectNode r : t.get(table)) {
                if (!r.hasNonNull("account_id") || !accounts.contains(r.get("account_id").asText())) {
                    throw new ImportException("table " + table + " references an account that is not in account_registry");
                }
            }
        }
        for (ObjectNode r : t.get("account_source_state")) {
            if (!KNOWN_SOURCES.contains(r.path("source").asText())) throw new ImportException("account_source_state has a source this schema does not support");
        }
        Set<String> chars = new HashSet<>();
        for (ObjectNode r : t.get("characters")) {
            if (!chars.add(r.path("account_id").asText() + "/" + r.path("character_id").asText())) throw new ImportException("characters has a duplicate identity");
        }
        for (String table : FixtureSnapshot.SCOPED_TABLES) {
            if (!t.get(table).isEmpty() && t.get(table).get(0).has("character_id") && !table.equals("characters")) {
                for (ObjectNode r : t.get(table)) {
                    if (!chars.contains(r.path("account_id").asText() + "/" + r.path("character_id").asText())) {
                        throw new ImportException("table " + table + " references a character that is not in characters");
                    }
                }
            }
        }
        // Optional parent coverage must be consistent with the parent source marker.
        Map<String, Long> charGen = new HashMap<>();
        for (ObjectNode r : t.get("account_source_state")) {
            if ("CHARACTERS".equals(r.path("source").asText())) charGen.put(r.path("account_id").asText(), r.path("generation").asLong());
        }
        for (String cov : List.of("character_equipment_coverage", "character_build_coverage")) {
            for (ObjectNode r : t.get(cov)) {
                Long g = charGen.get(r.path("account_id").asText());
                if (g == null || g != r.path("parent_generation").asLong()) throw new ImportException("table " + cov + " does not match the CHARACTERS marker generation");
            }
        }
    }

    // ------------------------------------------------------------------ identity remapping

    /** Deterministic fake principals: the same snapshot always maps to the same fake IDs, never to real ones. */
    static Map<String, List<ObjectNode>> remap(Map<String, List<ObjectNode>> source) throws ImportException {
        Map<String, String> account = new HashMap<>();
        List<String> accountOrder = new ArrayList<>(keys(source.get("account_registry"), "account_id"));
        java.util.Collections.sort(accountOrder);
        for (int i = 0; i < accountOrder.size(); i++) {
            account.put(accountOrder.get(i), UUID.nameUUIDFromBytes(("kpi-fake-account-" + i).getBytes(StandardCharsets.UTF_8)).toString());
        }
        Map<String, Integer> charId = new HashMap<>();
        Map<String, String> charName = new HashMap<>();
        Map<String, Integer> perAccount = new HashMap<>();
        List<ObjectNode> chars = new ArrayList<>(source.get("characters"));
        chars.sort(java.util.Comparator.comparing((ObjectNode r) -> r.path("account_id").asText()).thenComparingLong(r -> r.path("character_id").asLong()));
        for (ObjectNode c : chars) {
            String a = c.path("account_id").asText();
            int n = perAccount.merge(a, 1, Integer::sum);
            String key = a + "/" + c.path("character_id").asText();
            charId.put(key, n);
            charName.put(a + "/" + c.path("name").asText(), "Fake Character " + accountOrder.indexOf(a) + "-" + n);
        }
        Map<String, List<ObjectNode>> out = new LinkedHashMap<>();
        for (var e : source.entrySet()) {
            List<ObjectNode> rows = new ArrayList<>();
            for (ObjectNode r : e.getValue()) {
                ObjectNode c = r.deepCopy();
                if (FixtureSnapshot.SCOPED_TABLES.contains(e.getKey())) {
                    String realAccount = r.get("account_id").asText();
                    c.put("account_id", account.get(realAccount));
                    if (c.has("character_id")) c.put("character_id", charId.get(realAccount + "/" + r.get("character_id").asText()));
                    if (e.getKey().equals("characters")) c.put("name", charName.get(realAccount + "/" + r.get("name").asText()));
                    if (e.getKey().equals("account_registry")) c.put("display_name", "Fake account " + accountOrder.indexOf(realAccount));
                    // Bound-item names are real character/account names: remap known ones, blank the rest.
                    for (String f : List.of("bound_to")) {
                        if (c.hasNonNull(f)) c.put(f, charName.getOrDefault(realAccount + "/" + r.get(f).asText(), "Fake bound owner"));
                    }
                }
                rows.add(c);
            }
            out.put(e.getKey(), rows);
        }
        return out;
    }

    /**
     * Explicit, reported clock transformation: shifts every stored timestamp by the same delta so the
     * newest account marker equals {@code newestMarkerAt}. It is fixture preparation, never a claim that
     * the captured data is current.
     */
    public static FixtureSnapshot shiftClock(FixtureSnapshot s, OffsetDateTime newestMarkerAt) throws ImportException {
        OffsetDateTime newest = null;
        for (ObjectNode r : s.rows("account_source_state")) {
            OffsetDateTime v = parse(r.path("completed_at").asText());
            if (newest == null || v.isAfter(newest)) newest = v;
        }
        if (newest == null) throw new ImportException("no account_source_state marker to anchor a clock transformation");
        Duration delta = Duration.between(newest, newestMarkerAt);
        Map<String, List<ObjectNode>> shifted = new LinkedHashMap<>();
        for (var e : s.tables().entrySet()) {
            List<ObjectNode> rows = new ArrayList<>();
            for (ObjectNode r : e.getValue()) {
                ObjectNode c = r.deepCopy();
                for (String f : TIME_COLUMNS) {
                    if (c.hasNonNull(f)) c.put(f, parse(c.get(f).asText()).plus(delta).toString());
                }
                rows.add(c);
            }
            shifted.put(e.getKey(), rows);
        }
        return new FixtureSnapshot(s.kind(), s.version(), shifted, s.expected(), s.sourceRevision(),
                "uniform shift of " + delta.toSeconds() + "s applied to all stored timestamps (fixture preparation)");
    }

    private static OffsetDateTime parse(String v) throws ImportException {
        try {
            return OffsetDateTime.parse(v.replace(' ', 'T').replaceAll("([+-]\\d\\d)$", "$1:00")).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ImportException("a stored timestamp is not parseable");
        }
    }

}
