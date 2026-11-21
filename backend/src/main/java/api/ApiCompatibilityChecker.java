package api;

import api.ApiCompatibilityResult.Probe;
import api.ApiCompatibilityResult.State;
import api.ApiCompatibilityResult.Status;
import api.ApiCompatibilityResult.VersionEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import parser.RecipeParser;
import sync.FullCatalogSupport;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Bounded public-API compatibility check (STORY-OPS-008). Compares a fixed, small sample of live
 * public ArenaNet responses with the fields and types this application consumes, using the real
 * {@link RecipeParser} for recipe expectations. Never compares prices, catalog sizes, array order or
 * whole documents; additive fields are compatible. At most {@value #MAX_REQUESTS} requests with fixed
 * URLs, a {@value #TOTAL_MINUTES}-minute cooperative whole-check bound, no retries, no account or
 * authenticated endpoints and no mutation.
 *
 * <p>Classification: timeout/429/5xx/unreachable/not-run is {@code UNAVAILABLE}, never a proved
 * schema change; malformed JSON or a missing/wrong-typed consumed field is {@code MISMATCH}; a newly
 * advertised schema version, the known Jade profile gap and a directory/payload discrepancy are
 * {@code ATTENTION}.
 */
public class ApiCompatibilityChecker {

    public static final String BASE = "https://api.guildwars2.com";
    public static final String DIRECTORY_URL = BASE + "/v2.json?v=latest";
    public static final String CHANGELOG_URL = "https://wiki.guildwars2.com/wiki/API:Changelog";
    public static final String SCHEMA_DOC_URL = "https://wiki.guildwars2.com/wiki/API:2#Schemas";

    /** The one recipe schema production {@code RecipeSync} requests for catalog, details and validation. */
    public static final String PINNED_RECIPE_VERSION = sync.RecipeSync.SCHEMA_VERSION;
    /** Honest label of the production recipe requests: explicitly pinned, typed Item/Currency ingredients. */
    public static final String RECIPE_PROFILE = "v=" + PINNED_RECIPE_VERSION + " (explicit, production)";
    static final String PINNED_PROFILE = RECIPE_PROFILE;
    static final String DEFAULT_PROFILE = "default (unpinned)";

    /** Explicitly reviewed schema versions; a newer advertised one needs owner review, not an alarm. */
    public static final Set<String> REVIEWED_VERSIONS = Set.of(
            "2019-02-21T00:00:00.000Z", "2019-03-22T00:00:00.000Z", "2019-05-16T00:00:00.000Z",
            "2019-05-21T23:00:00.000Z", "2019-05-22T00:00:00.000Z", "2019-12-19T00:00:00.000Z",
            "2020-11-17T00:30:00.000Z", "2021-04-06T21:00:00.000Z", "2021-07-15T13:00:00.000Z",
            "2022-03-09T02:00:00.000Z", "2022-03-23T19:00:00.000Z", "2024-07-20T01:00:00.000Z",
            "2025-08-29T01:00:00.000Z");

    static final int ITEM_ID = 19721;
    static final int RECIPE_ID = 7319;
    static final int JADE_RECIPE_ID = 13839;
    /** Fixed identity of the Jade canary: its output item and the Research Notes currency it must still require. */
    static final int JADE_OUTPUT_ITEM_ID = 97487;
    static final int JADE_CURRENCY_ID = 61;
    static final int MAX_REQUESTS = 7;
    static final int TOTAL_MINUTES = 2;
    static final int MAX_VERSIONS = 64;
    static final int MAX_DESC = 240;
    static final int MAX_BODY_CHARS = PublicApiTransport.MAX_BODY_BYTES;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PublicApiTransport transport;
    private final Clock clock;
    private final LongSupplier nanoClock;

    public ApiCompatibilityChecker(PublicApiTransport transport, Clock clock, LongSupplier nanoClock) {
        this.transport = transport;
        this.clock = clock;
        this.nanoClock = nanoClock;
    }

    public static ApiCompatibilityChecker live() {
        return new ApiCompatibilityChecker(PublicApiTransport.live(), Clock.systemUTC(), System::nanoTime);
    }

    /** Run state of one check. */
    private final class Run {
        final TaskDeadline deadline = TaskDeadline.after(Duration.ofMinutes(TOTAL_MINUTES), nanoClock);
        final List<Probe> probes = new ArrayList<>();
        int requests;
        boolean stopped;

        /** @return the parsed body, or {@code null} after recording why the probe could not be judged */
        Fetched fetch(String id, String label, String endpoint, String profile, String url) {
            if (stopped || requests >= MAX_REQUESTS) {
                add(id, label, endpoint, profile, Status.UNAVAILABLE, "NOT_RUN", "Not run: the check was cut short.");
                return null;
            }
            try {
                deadline.check();
            } catch (TaskDeadline.ExceededException e) {
                stopped = true;
                add(id, label, endpoint, profile, Status.UNAVAILABLE, "TIME_BOUND", "Not run: the time bound was reached.");
                return null;
            } catch (InterruptedException e) {
                stopped = true;
                Thread.currentThread().interrupt();
                add(id, label, endpoint, profile, Status.UNAVAILABLE, "CANCELLED", "Not run: the check was interrupted.");
                return null;
            }
            requests++;
            PublicApiTransport.Response res;
            try {
                res = transport.get(url);
            } catch (HttpTimeoutException e) {
                add(id, label, endpoint, profile, Status.UNAVAILABLE, "TIMEOUT", "ArenaNet did not answer in time.");
                return null;
            } catch (IOException | RuntimeException e) {
                add(id, label, endpoint, profile, Status.UNAVAILABLE, "UNREACHABLE", "ArenaNet could not be reached.");
                return null;
            } catch (InterruptedException e) {
                stopped = true;
                Thread.currentThread().interrupt();
                add(id, label, endpoint, profile, Status.UNAVAILABLE, "CANCELLED", "The check was interrupted.");
                return null;
            }
            if (res == null) {
                add(id, label, endpoint, profile, Status.UNAVAILABLE, "UNREACHABLE", "ArenaNet could not be reached.");
                return null;
            }
            int s = res.status();
            if (s == 429) {
                add(id, label, endpoint, profile, Status.UNAVAILABLE, "RATE_LIMITED", "ArenaNet rate-limited the request (HTTP 429).");
                return null;
            }
            if (s >= 500) {
                add(id, label, endpoint, profile, Status.UNAVAILABLE, "SERVER_ERROR", "ArenaNet answered with a server error (HTTP " + s + ").");
                return null;
            }
            if (s == 404) return new Fetched(404, null);
            if (s != 200 && s != 206) {
                add(id, label, endpoint, profile, Status.MISMATCH, "UNEXPECTED_STATUS", "Unexpected HTTP status " + s + ".");
                return null;
            }
            String body = res.body();
            if (body == null || body.length() > MAX_BODY_CHARS) {
                add(id, label, endpoint, profile, Status.MISMATCH, "MALFORMED_PAYLOAD", "The response was empty or larger than the bound.");
                return null;
            }
            try {
                JsonNode node = MAPPER.readTree(body);
                if (node == null || node.isMissingNode()) throw new IOException("empty");
                return new Fetched(s, node);
            } catch (IOException e) {
                add(id, label, endpoint, profile, Status.MISMATCH, "MALFORMED_PAYLOAD", "The response was not valid JSON.");
                return null;
            }
        }

        void add(String id, String label, String endpoint, String profile, Status st, String code, String detail) {
            probes.add(new Probe(id, label, endpoint, profile, st, code, detail));
        }
    }

    private record Fetched(int status, JsonNode json) {}

    /** The fixed request asks for currencies 1 and 2: both distinct IDs must come back, once each, in any order. */
    private static String currencyIdentityProblem(JsonNode arr) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (JsonNode c : arr) {
            int id = c.path("id").asInt();
            if (id != 1 && id != 2) return "unrequested currency id " + id;
            if (!seen.add(id)) return "duplicate currency id " + id;
        }
        if (!seen.containsAll(java.util.Set.of(1, 2))) return "requested currency id missing (expected 1 and 2)";
        return null;
    }

    /** Runs the whole check; never throws for upstream behavior. */
    public ApiCompatibilityResult check() {
        long started = nanoClock.getAsLong();
        Run run = new Run();
        List<VersionEntry> versions = List.of();
        List<String> newer = List.of();
        String pinnedDesc = null;
        boolean pinnedAdvertised = false;

        // 1. Version directory
        Fetched dir = run.fetch("directory", "Version directory", "/v2.json?v=latest", DEFAULT_PROFILE, DIRECTORY_URL);
        if (dir != null) {
            var parsed = parseDirectory(dir.json());
            if (parsed == null) {
                run.add("directory", "Version directory", "/v2.json?v=latest", DEFAULT_PROFILE, Status.MISMATCH,
                        "DIRECTORY_MALFORMED", "schema_versions is missing or has entries without text v/desc.");
            } else {
                versions = parsed;
                newer = versions.stream().filter(v -> !v.reviewed()).map(VersionEntry::version).toList();
                for (VersionEntry v : versions) {
                    if (PINNED_RECIPE_VERSION.equals(v.version())) { pinnedAdvertised = true; pinnedDesc = v.description(); }
                }
                if (newer.isEmpty()) {
                    run.add("directory", "Version directory", "/v2.json?v=latest", DEFAULT_PROFILE, Status.OK,
                            "DIRECTORY_REVIEWED", versions.size() + " schema versions, all in the reviewed baseline.");
                } else {
                    run.add("directory", "Version directory", "/v2.json?v=latest", DEFAULT_PROFILE, Status.ATTENTION,
                            "NEW_VERSION", newer.size() + " newer schema version(s) than the reviewed baseline; review the changelog. This is not proof of breakage.");
                }
            }
        }

        // 2. Item
        Fetched item = run.fetch("item", "Item detail", "/v2/items/{id}", DEFAULT_PROFILE, BASE + "/v2/items/" + ITEM_ID);
        if (item != null) {
            judge(run, "item", "Item detail", "/v2/items/{id}", DEFAULT_PROFILE, item, ITEM_ID,
                    ApiCompatibilityChecker::itemProblem);
        }

        // 3. Ordinary legacy recipe through the production parser
        Fetched recipe = run.fetch("recipe", "Recipe detail", "/v2/recipes/{id}", RECIPE_PROFILE,
                BASE + "/v2/recipes/" + RECIPE_ID + "?v=" + PINNED_RECIPE_VERSION);
        if (recipe != null) {
            judge(run, "recipe", "Recipe detail", "/v2/recipes/{id}", RECIPE_PROFILE, recipe, RECIPE_ID,
                    ApiCompatibilityChecker::recipeProblem);
        }

        // 4. Trading Post price (shape only; never the prices)
        Fetched tp = run.fetch("prices", "Trading Post price", "/v2/commerce/prices/{id}", DEFAULT_PROFILE,
                BASE + "/v2/commerce/prices/" + ITEM_ID);
        if (tp != null) {
            judge(run, "prices", "Trading Post price", "/v2/commerce/prices/{id}", DEFAULT_PROFILE, tp, ITEM_ID, n -> {
                // Reuse the production strict quote contract (positive int id, null/null no-data or two
                // non-negative sides, long quantity) so the check cannot diverge from the real sync.
                try {
                    sync.TpSync.strictQuote(n);
                } catch (IllegalStateException e) {
                    return "quote (rejected by the production Trading Post validator)";
                }
                return null;
            });
        }

        // 5. Currency metadata
        Fetched cur = run.fetch("currencies", "Currency metadata", "/v2/currencies?ids=1,2", DEFAULT_PROFILE,
                BASE + "/v2/currencies?ids=1,2");
        if (cur != null) {
            if (cur.status() == 404 || cur.json() == null || !cur.json().isArray() || cur.json().isEmpty()) {
                run.add("currencies", "Currency metadata", "/v2/currencies?ids=1,2", DEFAULT_PROFILE, Status.MISMATCH,
                        "SHAPE_MISMATCH", "Expected a non-empty array of currencies.");
            } else {
                String bad = null;
                for (JsonNode c : cur.json()) {
                    bad = c.isObject() ? missing(c, intField("id"), textField("name")) : "entry";
                    if (bad != null) break;
                }
                if (bad == null) bad = currencyIdentityProblem(cur.json());
                if (bad == null) {
                    run.add("currencies", "Currency metadata", "/v2/currencies?ids=1,2", DEFAULT_PROFILE, Status.OK,
                            "COMPATIBLE", "Consumed currency fields have the expected types.");
                } else {
                    run.add("currencies", "Currency metadata", "/v2/currencies?ids=1,2", DEFAULT_PROFILE, Status.MISMATCH,
                            "SHAPE_MISMATCH", "Missing or wrong-typed field: " + bad + ".");
                }
            }
        }

        // 6. Jade canary, current default profile
        Fetched jadeDefault = run.fetch("jade-default", "Jade recipe, current default", "/v2/recipes/13839", DEFAULT_PROFILE,
                BASE + "/v2/recipes/" + JADE_RECIPE_ID);
        if (jadeDefault != null) {
            if (jadeDefault.status() == 404) {
                run.add("jade-default", "Jade recipe, current default", "/v2/recipes/13839", DEFAULT_PROFILE, Status.OK,
                        "DEFAULT_SCHEMA_OMITS_RECIPE", "Absent from the default schema, as expected; production requests the explicit pinned schema, so this is informational only.");
            } else {
                String problem = recipeProblem(jadeDefault.json());
                run.add("jade-default", "Jade recipe, current default", "/v2/recipes/13839", DEFAULT_PROFILE,
                        Status.OK, "COMPATIBLE",
                        "Present in the default schema" + (problem == null ? "." : " (differently shaped: " + problem + "); production uses the pinned schema."));
            }
        }

        // 7. Jade canary under the explicit supported 2022 schema
        Fetched jadePinned = run.fetch("jade-pinned", "Jade recipe, explicit 2022 schema", "/v2/recipes/13839?v=2022-03-09",
                PINNED_PROFILE, BASE + "/v2/recipes/" + JADE_RECIPE_ID + "?v=" + PINNED_RECIPE_VERSION);
        if (jadePinned != null) judgeJadePinned(run, jadePinned, pinnedAdvertised, pinnedDesc);

        List<Probe> probes = List.copyOf(run.probes);
        long ms = Math.max(0L, (nanoClock.getAsLong() - started) / 1_000_000L);
        return new ApiCompatibilityResult(State.COMPLETE, clock.instant(), ms, ApiCompatibilityResult.worst(probes),
                RECIPE_PROFILE, versions, newer, probes);
    }

    private void judgeJadePinned(Run run, Fetched f, boolean advertised, String desc) {
        String id = "jade-pinned", label = "Jade recipe, explicit 2022 schema", ep = "/v2/recipes/13839?v=2022-03-09";
        if (f.status() == 404 || f.json() == null || !f.json().isObject()) {
            run.add(id, label, ep, PINNED_PROFILE, Status.MISMATCH, "SHAPE_MISMATCH", "The explicit schema no longer returns the Jade recipe.");
            return;
        }
        JsonNode n = f.json();
        String base = missing(n, intField("id"), textField("type"), intField("output_item_id"), intField("output_item_count"));
        List<JsonNode> typed = typedIngredients(n);
        if (base != null || typed == null) {
            run.add(id, label, ep, PINNED_PROFILE, Status.MISMATCH, "SHAPE_MISMATCH",
                    base != null ? "Missing or wrong-typed field: " + base + "." : "No typed ingredient list in the explicit schema.");
            return;
        }
        for (JsonNode ing : typed) {
            String kind = ing.path("type").asText("");
            boolean ok = (kind.equals("Item") || kind.equals("Currency")) && ing.path("id").isInt() && ing.path("id").asInt() > 0 && ing.path("count").isInt();
            if (!ok) {
                run.add(id, label, ep, PINNED_PROFILE, Status.MISMATCH, "UNSUPPORTED_INGREDIENT",
                        "An ingredient has an unsupported kind or wrong-typed id/count.");
                return;
            }
        }
        if (n.path("id").asInt(-1) != JADE_RECIPE_ID || n.path("output_item_id").asInt(-1) != JADE_OUTPUT_ITEM_ID) {
            run.add(id, label, ep, PINNED_PROFILE, Status.MISMATCH, "SHAPE_MISMATCH",
                    "The canary answered with a different recipe or output item than the fixed sample.");
            return;
        }
        if (n.path("output_item_count").asInt(0) <= 0) {
            run.add(id, label, ep, PINNED_PROFILE, Status.MISMATCH, "SHAPE_MISMATCH", "The canary output count is not positive.");
            return;
        }
        boolean hasResearchNotes = false;
        for (JsonNode ing : typed) {
            if (ing.path("count").asInt(0) <= 0) {
                run.add(id, label, ep, PINNED_PROFILE, Status.MISMATCH, "SHAPE_MISMATCH", "An ingredient quantity is not positive.");
                return;
            }
            if ("Currency".equals(ing.path("type").asText()) && ing.path("id").asInt(-1) == JADE_CURRENCY_ID) hasResearchNotes = true;
        }
        if (!hasResearchNotes) {
            run.add(id, label, ep, PINNED_PROFILE, Status.MISMATCH, "SHAPE_MISMATCH",
                    "The currency ingredient (Research Notes, currency 61) is no longer required by the canary.");
            return;
        }
        boolean describesSplit = desc != null && desc.contains("item_ingredients");
        if (advertised && describesSplit && !n.has("item_ingredients")) {
            run.add(id, label, ep, PINNED_PROFILE, Status.OK, "DIRECTORY_DESCRIPTION_SUPPORTED",
                    "Expected per the directory description: split item_ingredients/currency_ingredients lists. Actual: one typed ingredients list (Item/Currency, including Research Notes currency 61). "
                            + "Production supports the typed list, and every identity, ingredient and quantity check passed, so this description-only difference is compatible.");
        } else if (!advertised) {
            run.add(id, label, ep, PINNED_PROFILE, Status.ATTENTION, "DIRECTORY_PAYLOAD_DISCREPANCY",
                    "The schema version still answers but is no longer advertised by the directory.");
        } else {
            run.add(id, label, ep, PINNED_PROFILE, Status.OK, "COMPATIBLE", "Typed Item/Currency ingredients as expected.");
        }
    }

    /** Shared 404/shape judgement for sample resources that must exist. */
    private void judge(Run run, String id, String label, String endpoint, String profile, Fetched f, int expectedId,
                       java.util.function.Function<JsonNode, String> problem) {
        if (f.status() == 404 || f.json() == null || !f.json().isObject()) {
            run.add(id, label, endpoint, profile, Status.MISMATCH, "SHAPE_MISMATCH", "The fixed sample was not found or is not an object.");
            return;
        }
        String bad = problem.apply(f.json());
        if (bad != null) {
            boolean unsupported = bad.startsWith("unsupported ingredient");
            run.add(id, label, endpoint, profile, Status.MISMATCH, unsupported ? "UNSUPPORTED_INGREDIENT" : "SHAPE_MISMATCH",
                    unsupported ? "An ingredient kind our parser cannot read." : "Missing or wrong-typed field: " + bad + ".");
        } else if (f.json().path("id").asInt(-1) != expectedId) {
            run.add(id, label, endpoint, profile, Status.MISMATCH, "SHAPE_MISMATCH", "The sample answered with a different id.");
        } else {
            run.add(id, label, endpoint, profile, Status.OK, "COMPATIBLE", "Consumed fields have the expected types.");
        }
    }

    /**
     * Consumed item fields: id/name/type/rarity are required; vendor_value, icon and flags are optional
     * (absent or null is legitimate) but, when present, must have their real types, never coerced to 0.
     */
    static String itemProblem(JsonNode n) {
        String m = missing(n, intField("id"), textField("name"), textField("type"), textField("rarity"));
        if (m != null) return m;
        JsonNode vv = n.get("vendor_value");
        if (vv != null && !vv.isNull() && !(vv.isIntegralNumber() && vv.canConvertToInt() && vv.asInt() >= 0)) return "vendor_value";
        JsonNode icon = n.get("icon");
        if (icon != null && !icon.isNull() && !icon.isTextual()) return "icon";
        JsonNode flags = n.get("flags");
        if (flags != null && !flags.isNull()) {
            if (!flags.isArray()) return "flags";
            for (JsonNode f : flags) if (!f.isTextual()) return "flags";
        }
        return null;
    }

    /** Real production parser expectations for a legacy recipe; {@code null} when readable. */
    static String recipeProblem(JsonNode n) {
        if (n == null || !n.isObject()) return "recipe";
        String m = missing(n, intField("id"), textField("type"), intField("output_item_id"),
                intField("output_item_count"), arrayField("disciplines"), arrayField("ingredients"));
        if (m != null) return m;
        JsonNode ings = n.get("ingredients");
        if (ings.isEmpty()) return "ingredients";
        for (JsonNode ing : ings) {
            boolean typed = ing.has("type");
            if (typed && !"Item".equals(ing.path("type").asText()) && !"Currency".equals(ing.path("type").asText()))
                return "unsupported ingredient";
            if (!ing.path(typed ? "id" : "item_id").isInt() || !ing.path("count").isInt()) return "ingredients";
        }
        if (RecipeParser.parseRecipe(n) == null) return "recipe";
        // Every requirement must reach either the item or the currency rows: nothing silently dropped.
        if (RecipeParser.parseIngredients(n).size() + RecipeParser.parseNonItemRequirements(n).size() != ings.size())
            return "unsupported ingredient";
        // The same strict validator the production full-catalog refresh applies (min_rating, time_to_craft_ms, ...).
        try {
            FullCatalogSupport.requireRecipe(n);
        } catch (IllegalStateException e) {
            return "recipe (production validation)";
        }
        return null;
    }

    /** Typed ingredients from either the {@code ingredients} or the split representation, or null. */
    private static List<JsonNode> typedIngredients(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode ings = n.get("ingredients");
        if (ings != null && !ings.isArray()) return null;
        if (ings != null && !ings.isEmpty()) {
            ings.forEach(out::add);
            return out;
        }
        boolean any = false;
        for (String key : new String[] {"item_ingredients", "currency_ingredients"}) {
            JsonNode part = n.get(key);
            // A present but wrong-typed split list must not be silently ignored.
            if (part != null && !part.isArray()) return null;
            if (part != null) {
                any = true;
                String kind = key.startsWith("item") ? "Item" : "Currency";
                for (JsonNode p : part) {
                    var copy = MAPPER.createObjectNode().put("type", kind);
                    copy.set("id", p.get("id"));
                    copy.set("count", p.get("count"));
                    out.add(copy);
                }
            }
        }
        return any && !out.isEmpty() ? out : null;
    }

    private static List<VersionEntry> parseDirectory(JsonNode root) {
        if (root == null || !root.isObject()) return null;
        JsonNode sv = root.get("schema_versions");
        if (sv == null || !sv.isArray() || sv.isEmpty()) return null;
        List<VersionEntry> out = new ArrayList<>();
        for (JsonNode e : sv) {
            if (!e.isObject() || !e.path("v").isTextual() || !e.path("desc").isTextual()) return null;
            String v = e.get("v").asText();
            if (v.length() > 40) return null;
            out.add(new VersionEntry(v, clean(e.get("desc").asText()), REVIEWED_VERSIONS.contains(v)));
        }
        out.sort(java.util.Comparator.comparing(VersionEntry::version));
        // Keep the newest bounded window; advertised-but-unreviewed entries are always newest-or-unknown.
        return out.size() > MAX_VERSIONS ? List.copyOf(out.subList(out.size() - MAX_VERSIONS, out.size())) : List.copyOf(out);
    }

    private static String clean(String s) {
        String t = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return t.length() > MAX_DESC ? t.substring(0, MAX_DESC) + "…" : t;
    }

    // ---- tiny field-contract helpers ----
    private record Field(String name, java.util.function.Predicate<JsonNode> ok) {}
    private static Field intField(String n) { return new Field(n, x -> x.isIntegralNumber() && x.canConvertToInt()); }
    private static Field textField(String n) { return new Field(n, x -> x.isTextual() && !x.asText().isBlank()); }
    private static Field arrayField(String n) { return new Field(n, JsonNode::isArray); }

    /** @return the first missing or wrong-typed field name, or {@code null} */
    private static String missing(JsonNode n, Field... fields) {
        for (Field f : fields) {
            JsonNode v = n.get(f.name());
            if (v == null || !f.ok().test(v)) return f.name();
        }
        return null;
    }
}
