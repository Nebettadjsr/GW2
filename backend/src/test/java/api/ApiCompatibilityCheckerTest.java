package api;

import api.ApiCompatibilityResult.Probe;
import api.ApiCompatibilityResult.State;
import api.ApiCompatibilityResult.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** STORY-OPS-008: deterministic offline classification with a fake transport and clocks. */
class ApiCompatibilityCheckerTest {

    static final String B = ApiCompatibilityChecker.BASE;
    static final String PINNED = ApiCompatibilityChecker.PINNED_RECIPE_VERSION;

    static final String DIRECTORY = """
            {"langs":["en"],"routes":[{"path":"/v2/items"}],"schema_versions":[
            {"v":"2019-02-21T00:00:00.000Z","desc":"Add last_modified."},
            {"v":"2022-03-09T02:00:00.000Z","desc":"Change `ingredients` to `item_ingredients` and add `currency_ingredients` to `/v2/recipes`."},
            {"v":"2025-08-29T01:00:00.000Z","desc":"Remove mount."}]}""";
    static final String ITEM = "{\"id\":19721,\"name\":\"Glob of Ectoplasm\",\"type\":\"CraftingMaterial\",\"rarity\":\"Rare\",\"extra\":{\"new\":1}}";
    static final String RECIPE = "{\"id\":7319,\"type\":\"Refinement\",\"output_item_id\":46742,\"output_item_count\":1,"
            + "\"time_to_craft_ms\":1000,\"disciplines\":[\"Artificer\"],\"min_rating\":400,\"flags\":[],"
            + "\"ingredients\":[{\"type\":\"Item\",\"id\":19721,\"count\":1}],\"chat_link\":\"[&x]\"}";
    /** Production requests the explicit pinned schema for every recipe, so the ordinary canary does too. */
    static final String RECIPE_URL = B + "/v2/recipes/7319?v=2022-03-09T02:00:00.000Z";
    static final String PRICE = "{\"id\":19721,\"buys\":{\"quantity\":5,\"unit_price\":10},\"sells\":{\"quantity\":6,\"unit_price\":12}}";
    static final String CURRENCIES = "[{\"id\":1,\"name\":\"Coin\"},{\"id\":2,\"name\":\"Karma\"}]";
    static final String JADE_TYPED = "{\"id\":13839,\"type\":\"Refinement\",\"output_item_id\":97487,\"output_item_count\":1,"
            + "\"ingredients\":[{\"type\":\"Item\",\"id\":97102,\"count\":4},{\"type\":\"Currency\",\"id\":61,\"count\":30}]}";

    private final Map<String, Object> responses = new HashMap<>();
    private final List<String> requested = new ArrayList<>();
    private final AtomicLong nanos = new AtomicLong();

    @BeforeEach
    void healthy() {
        ok(ApiCompatibilityChecker.DIRECTORY_URL, DIRECTORY);
        ok(B + "/v2/items/19721", ITEM);
        ok(RECIPE_URL, RECIPE);
        ok(B + "/v2/commerce/prices/19721", PRICE);
        ok(B + "/v2/currencies?ids=1,2", CURRENCIES);
        responses.put(B + "/v2/recipes/13839", new PublicApiTransport.Response(404, "{\"text\":\"no such id\"}"));
        ok(B + "/v2/recipes/13839?v=" + PINNED, JADE_TYPED);
    }

    private void ok(String url, String body) { responses.put(url, new PublicApiTransport.Response(200, body)); }

    private ApiCompatibilityResult run() {
        PublicApiTransport t = url -> {
            requested.add(url);
            Object r = responses.get(url);
            if (r instanceof IOException e) throw e;
            if (r instanceof RuntimeException e) throw e;
            return (PublicApiTransport.Response) r;
        };
        return new ApiCompatibilityChecker(t, Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC),
                nanos::get).check();
    }

    private static Probe probe(ApiCompatibilityResult r, String id) {
        return r.probes().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void compatibleShapesWithAdditiveFieldsAreOk_jadeIsAttentionNeverFalselyGreen() {
        var r = run();
        assertEquals(State.COMPLETE, r.state());
        assertEquals(Instant.parse("2026-11-19T10:00:00Z"), r.checkedAt());
        assertEquals(Status.OK, probe(r, "item").status());
        assertEquals(Status.OK, probe(r, "recipe").status());
        assertEquals(Status.OK, probe(r, "prices").status());
        assertEquals(Status.OK, probe(r, "currencies").status());
        assertEquals(Status.OK, probe(r, "directory").status());
        // The default schema omits Jade, which is expected and informational: production pins the schema.
        assertEquals("DEFAULT_SCHEMA_OMITS_RECIPE", probe(r, "jade-default").code());
        assertEquals(Status.OK, probe(r, "jade-default").status());
        // The directory's item_ingredients wording differs from the typed payload: supported, so compatible.
        assertEquals("DIRECTORY_DESCRIPTION_SUPPORTED", probe(r, "jade-pinned").code());
        assertEquals(Status.OK, probe(r, "jade-pinned").status());
        assertTrue(probe(r, "jade-pinned").detail().contains("item_ingredients"));
        assertTrue(probe(r, "jade-pinned").detail().contains("typed ingredients list"));
        assertEquals(Status.OK, r.overall());
        assertEquals(ApiCompatibilityChecker.RECIPE_PROFILE, r.recipeProfile());
        assertTrue(r.recipeProfile().contains("explicit, production"));
        assertEquals(ApiCompatibilityChecker.RECIPE_PROFILE, probe(r, "recipe").profile());
        assertTrue(requested.size() <= ApiCompatibilityChecker.MAX_REQUESTS);
        assertTrue(requested.stream().allMatch(u -> u.startsWith(B + "/v2")));
    }

    @Test
    void currencyProbeRequiresBothDistinctRequestedIds() {
        String missing = "[{\"id\":1,\"name\":\"Coin\"}]";
        ok(B + "/v2/currencies?ids=1,2", missing);
        assertEquals(Status.MISMATCH, probe(run(), "currencies").status());
        // Partial (206) response with only one of the requested ids.
        responses.put(B + "/v2/currencies?ids=1,2", new PublicApiTransport.Response(206, missing));
        assertEquals(Status.MISMATCH, probe(run(), "currencies").status());
        // Wrong id replaces a requested one.
        ok(B + "/v2/currencies?ids=1,2", "[{\"id\":1,\"name\":\"Coin\"},{\"id\":3,\"name\":\"Laurel\"}]");
        assertEquals(Status.MISMATCH, probe(run(), "currencies").status());
        // Duplicate id leaves the other missing.
        ok(B + "/v2/currencies?ids=1,2", "[{\"id\":1,\"name\":\"Coin\"},{\"id\":1,\"name\":\"Coin\"}]");
        assertEquals(Status.MISMATCH, probe(run(), "currencies").status());
        // Duplicate plus both present is still not the exact requested set.
        ok(B + "/v2/currencies?ids=1,2", "[{\"id\":1,\"name\":\"Coin\"},{\"id\":2,\"name\":\"Karma\"},{\"id\":2,\"name\":\"Karma\"}]");
        assertEquals(Status.MISMATCH, probe(run(), "currencies").status());
    }

    @Test
    void currencyProbeAcceptsReversedOrderWithAdditiveFields() {
        ok(B + "/v2/currencies?ids=1,2", "[{\"id\":2,\"name\":\"Karma\",\"order\":9,\"icon\":\"x\"},{\"id\":1,\"name\":\"Coin\",\"extra\":true}]");
        assertEquals(Status.OK, probe(run(), "currencies").status());
    }

    @Test
    void pinnedJadeWithoutDescriptionDiscrepancyIsOk() {
        ok(ApiCompatibilityChecker.DIRECTORY_URL, DIRECTORY.replace("`item_ingredients` and add `currency_ingredients`", "ingredients"));
        assertEquals(Status.OK, probe(run(), "jade-pinned").status());
    }

    @Test
    void splitItemAndCurrencyIngredientsAreAlsoUnderstood() {
        ok(B + "/v2/recipes/13839?v=" + PINNED, "{\"id\":13839,\"type\":\"Refinement\",\"output_item_id\":97487,\"output_item_count\":1,"
                + "\"item_ingredients\":[{\"id\":1,\"count\":1}],\"currency_ingredients\":[{\"id\":61,\"count\":3}]}");
        assertEquals("DIRECTORY_PAYLOAD_DISCREPANCY".equals(probe(run(), "jade-pinned").code()), false);
        assertEquals(Status.OK, probe(run(), "jade-pinned").status());
    }

    @Test
    void jadePresentInDefaultSchemaIsInformationalBecauseProductionIsPinned() {
        ok(B + "/v2/recipes/13839", JADE_TYPED);
        var p = probe(run(), "jade-default");
        assertEquals(Status.OK, p.status());
        assertEquals("COMPATIBLE", p.code());
    }

    @Test
    void missingAndTypeChangedFieldsAreMismatchesNamingOnlyOurField() {
        ok(B + "/v2/items/19721", "{\"id\":19721,\"type\":\"X\"}");
        ok(B + "/v2/commerce/prices/19721", "{\"id\":19721,\"buys\":{\"quantity\":1,\"unit_price\":\"10\"},\"sells\":{\"quantity\":1,\"unit_price\":1}}");
        var r = run();
        assertEquals(Status.MISMATCH, probe(r, "item").status());
        assertTrue(probe(r, "item").detail().contains("name"));
        assertEquals(Status.MISMATCH, probe(r, "prices").status());
        assertTrue(probe(r, "prices").detail().contains("quote"));
        assertEquals(Status.MISMATCH, r.overall());
        // The healthy probes are not erased, nor do they hide the mismatch.
        assertEquals(Status.OK, probe(r, "recipe").status());
    }

    private void priceIs(String body, Status expected) {
        ok(B + "/v2/commerce/prices/19721", body);
        assertEquals(expected, probe(run(), "prices").status(), body);
    }

    @Test
    void tradingPostQuoteFollowsTheProductionContract() {
        priceIs("{\"id\":19721,\"buys\":{\"quantity\":1,\"unit_price\":-1},\"sells\":{\"quantity\":1,\"unit_price\":1}}", Status.MISMATCH);
        priceIs("{\"id\":19721,\"buys\":{\"quantity\":1,\"unit_price\":1},\"sells\":{\"quantity\":-5,\"unit_price\":1}}", Status.MISMATCH);
        priceIs("{\"id\":19721,\"buys\":{\"quantity\":1,\"unit_price\":1},\"sells\":{\"quantity\":1,\"unit_price\":-2}}", Status.MISMATCH);
        priceIs("{\"id\":19721,\"buys\":{\"quantity\":-1,\"unit_price\":1},\"sells\":{\"quantity\":1,\"unit_price\":1}}", Status.MISMATCH);
        priceIs("{\"id\":19721,\"buys\":{\"quantity\":3000000000,\"unit_price\":1},\"sells\":{\"quantity\":1,\"unit_price\":1}}", Status.OK);
        priceIs("{\"id\":19721,\"buys\":null,\"sells\":null}", Status.OK);
        priceIs("{\"id\":19721,\"buys\":null,\"sells\":{\"quantity\":1,\"unit_price\":1}}", Status.MISMATCH);
        priceIs("{\"id\":19721}", Status.MISMATCH);
        priceIs("{\"id\":19722,\"buys\":null,\"sells\":null}", Status.MISMATCH);
    }

    @Test
    void wrongTypedSplitJadeIngredientListsAreNotSilentlyIgnored() {
        String base = "{\"id\":13839,\"type\":\"Refinement\",\"output_item_id\":97487,\"output_item_count\":1,";
        ok(B + "/v2/recipes/13839?v=" + PINNED, base + "\"item_ingredients\":{\"id\":1,\"count\":1},"
                + "\"currency_ingredients\":[{\"id\":61,\"count\":30}]}");
        assertEquals(Status.MISMATCH, probe(run(), "jade-pinned").status());
        ok(B + "/v2/recipes/13839?v=" + PINNED, base + "\"ingredients\":[{\"type\":\"Item\",\"id\":0,\"count\":1},"
                + "{\"type\":\"Currency\",\"id\":61,\"count\":30}]}");
        assertEquals(Status.MISMATCH, probe(run(), "jade-pinned").status());
    }

    @Test
    void aRecipeWithAnUnsupportedIngredientKindIsAnUnsupportedIngredientMismatch() {
        ok(RECIPE_URL, RECIPE.replace("{\"type\":\"Item\",\"id\":19721,\"count\":1}",
                "{\"type\":\"Item\",\"id\":19721,\"count\":1},{\"type\":\"GuildUpgrade\",\"id\":721,\"count\":8}"));
        var p = probe(run(), "recipe");
        assertEquals(Status.MISMATCH, p.status());
        assertEquals("UNSUPPORTED_INGREDIENT", p.code());
    }

    @Test
    void aSkippedIngredientlessPlaceholderIsNotAnApiFailureButOtherMalformedRecipesStillAre() {
        String placeholder = RECIPE.replace("[{\"type\":\"Item\",\"id\":19721,\"count\":1}]", "[]");
        ok(RECIPE_URL, placeholder);
        assertEquals(Status.OK, probe(run(), "recipe").status());
        ok(RECIPE_URL, placeholder.replace("\"min_rating\":400", "\"min_rating\":-1"));
        assertEquals(Status.MISMATCH, probe(run(), "recipe").status());
    }

    @Test
    void aRecipeWithAnUnreadableIngredientIsNotCompatible() {
        ok(RECIPE_URL, RECIPE.replace("\"count\":1}]", "\"count\":1},{\"type\":\"Currency\",\"id\":0,\"count\":1}]"));
        assertEquals(Status.MISMATCH, probe(run(), "recipe").status());
    }

    @Test
    void newerAdvertisedVersionIsAttentionDistinctFromMismatch() {
        ok(ApiCompatibilityChecker.DIRECTORY_URL, DIRECTORY.replace("]}", ",{\"v\":\"2026-12-01T00:00:00.000Z\",\"desc\":\"Something new\"}]}"));
        var r = run();
        assertEquals(List.of("2026-12-01T00:00:00.000Z"), r.newerVersions());
        assertEquals("NEW_VERSION", probe(r, "directory").code());
        assertEquals(Status.ATTENTION, probe(r, "directory").status());
        assertEquals(Status.OK, probe(r, "item").status());
        assertFalse(r.versions().stream().filter(v -> v.version().startsWith("2026")).findFirst().orElseThrow().reviewed());
    }

    @Test
    void malformedVersionDirectoryIsMismatchAndStillRunsOtherProbes() {
        ok(ApiCompatibilityChecker.DIRECTORY_URL, "{\"schema_versions\":[{\"v\":1}]}");
        var r = run();
        assertEquals("DIRECTORY_MALFORMED", probe(r, "directory").code());
        assertEquals(Status.MISMATCH, probe(r, "directory").status());
        assertTrue(r.versions().isEmpty());
        assertEquals(Status.OK, probe(r, "item").status());
        ok(ApiCompatibilityChecker.DIRECTORY_URL, "not json {");
        assertEquals("MALFORMED_PAYLOAD", probe(run(), "directory").code());
    }

    @Test
    void timeoutRateLimitServerErrorAndUnreachableAreUnavailableNotSchemaChanges() {
        responses.put(B + "/v2/items/19721", new HttpTimeoutException("secret-timeout-detail"));
        responses.put(RECIPE_URL, new PublicApiTransport.Response(429, "slow down"));
        responses.put(B + "/v2/commerce/prices/19721", new PublicApiTransport.Response(503, "<html>boom</html>"));
        responses.put(B + "/v2/currencies?ids=1,2", new IOException("connection refused to 10.0.0.1"));
        var r = run();
        assertEquals("TIMEOUT", probe(r, "item").code());
        assertEquals("RATE_LIMITED", probe(r, "recipe").code());
        assertEquals("SERVER_ERROR", probe(r, "prices").code());
        assertEquals("UNREACHABLE", probe(r, "currencies").code());
        for (String id : List.of("item", "recipe", "prices", "currencies")) assertEquals(Status.UNAVAILABLE, probe(r, id).status());
        assertEquals(Status.UNAVAILABLE, r.overall());
        String all = r.toString();
        assertFalse(all.contains("secret-timeout-detail") || all.contains("10.0.0.1") || all.contains("boom") || all.contains("slow down"));
    }

    @Test
    void emptyResponseAndWholeOutageAreNeverSuccessful() {
        responses.clear();
        var r = run();
        assertEquals(Status.UNAVAILABLE, r.overall());
        assertTrue(r.probes().stream().noneMatch(p -> p.status() == Status.OK));
        assertEquals(7, r.probes().size());
    }

    @Test
    void timeBoundStopsFurtherRequestsAndMarksThemNotRun() {
        PublicApiTransport t = url -> {
            requested.add(url);
            nanos.addAndGet(java.time.Duration.ofMinutes(3).toNanos());
            return (PublicApiTransport.Response) responses.get(url);
        };
        var r = new ApiCompatibilityChecker(t, Clock.systemUTC(), nanos::get).check();
        assertEquals(1, requested.size());
        assertEquals("TIME_BOUND", probe(r, "item").code());
        assertEquals(Status.UNAVAILABLE, probe(r, "jade-pinned").status());
    }

    @Test
    void directoryDescriptionNotAdvertisingPinnedVersionIsFlagged() {
        ok(ApiCompatibilityChecker.DIRECTORY_URL, "{\"schema_versions\":[{\"v\":\"2025-08-29T01:00:00.000Z\",\"desc\":\"x\"}]}");
        assertEquals("DIRECTORY_PAYLOAD_DISCREPANCY", probe(run(), "jade-pinned").code());
    }

    @Test
    void longDescriptionsAreBoundedAndControlCharactersRemoved() {
        ok(ApiCompatibilityChecker.DIRECTORY_URL, "{\"schema_versions\":[{\"v\":\"2025-08-29T01:00:00.000Z\",\"desc\":\""
                + "a\\n".repeat(500) + "\"}]}");
        var d = run().versions().get(0).description();
        assertTrue(d.length() <= ApiCompatibilityChecker.MAX_DESC + 1);
        assertFalse(d.contains("\n"));
    }

    @Test
    void consumedItemFieldsMustHaveRealTypesWhilePresentAndOptionalOnesMayBeAbsent() {
        String base = "{\"id\":19721,\"name\":\"Glob\",\"type\":\"CraftingMaterial\",\"rarity\":\"Rare\"";
        ok(B + "/v2/items/19721", base + "}"); // vendor_value, icon and flags legitimately absent
        assertEquals(Status.OK, probe(run(), "item").status());
        ok(B + "/v2/items/19721", base + ",\"vendor_value\":null,\"icon\":null,\"flags\":null}");
        assertEquals(Status.OK, probe(run(), "item").status());
        for (String bad : new String[] {",\"vendor_value\":\"7\"", ",\"vendor_value\":-1", ",\"vendor_value\":4294967296", ",\"vendor_value\":-4294967296", ",\"icon\":5",
                ",\"flags\":\"NoSell\"", ",\"flags\":[\"NoSell\",3]"}) {
            ok(B + "/v2/items/19721", base + bad + "}");
            var p = probe(run(), "item");
            assertEquals(Status.MISMATCH, p.status(), bad);
            assertEquals("SHAPE_MISMATCH", p.code(), bad);
        }
        ok(B + "/v2/items/19721", "{\"id\":19721,\"name\":\"Glob\",\"type\":\"CraftingMaterial\"}");
        assertTrue(probe(run(), "item").detail().contains("rarity"));
    }

    @Test
    void jadeCanaryMustKeepItsIdentityAndTheResearchNotesCurrency() {
        ok(B + "/v2/recipes/13839?v=" + PINNED, JADE_TYPED.replace("97487", "1"));
        assertEquals(Status.MISMATCH, probe(run(), "jade-pinned").status());
        ok(B + "/v2/recipes/13839?v=" + PINNED, JADE_TYPED.replace("\"id\":13839", "\"id\":1"));
        assertEquals(Status.MISMATCH, probe(run(), "jade-pinned").status());
        // Currency 61 disappeared: only an item ingredient is left.
        ok(B + "/v2/recipes/13839?v=" + PINNED, JADE_TYPED.replace("{\"type\":\"Currency\",\"id\":61,\"count\":30}", "{\"type\":\"Item\",\"id\":5,\"count\":1}"));
        var gone = probe(run(), "jade-pinned");
        assertEquals(Status.MISMATCH, gone.status());
        assertTrue(gone.detail().contains("currency 61"));
        // Currency 61 present but with a non-positive quantity.
        ok(B + "/v2/recipes/13839?v=" + PINNED, JADE_TYPED.replace("\"count\":30", "\"count\":0"));
        assertEquals(Status.MISMATCH, probe(run(), "jade-pinned").status());
    }

    @Test
    void ordinaryRecipeMustSatisfyTheProductionFullRefreshValidator() {
        ok(RECIPE_URL, RECIPE.replace("\"min_rating\":400,", ""));
        assertEquals(Status.MISMATCH, probe(run(), "recipe").status());
        ok(RECIPE_URL, RECIPE.replace("\"time_to_craft_ms\":1000,", ""));
        assertEquals(Status.MISMATCH, probe(run(), "recipe").status());
        ok(RECIPE_URL, RECIPE); // control: the unmodified fixture is compatible
        assertEquals(Status.OK, probe(run(), "recipe").status());
    }
}
