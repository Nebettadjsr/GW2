package web;

import craft.Ingredient;
import craft.MysticForgeCatalog;
import craft.PriceQuote;
import craft.Recipe;
import craft.RecipeSource;
import org.junit.jupiter.api.Test;
import repo.ItemRepository;
import repo.tp.TpPriceRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MysticForgeApiControllerTest {

    /** The catalogue reads cached metadata and quotes in two batched calls and never touches a planner or account. */
    @Test
    void catalogueIsCheapMetadataWithReliableSubtypeQuotesAndSeparateReferenceRows() throws Exception {
        Recipe bow = new Recipe(-1, 500, 1, 0, "", List.of(new Ingredient(1, 2)), List.of(), RecipeSource.MYSTIC_FORGE);
        Recipe promo = Recipe.forgeWithExpectedYield(-2, 600, craft.ExpectedYield.of("18.92", 10, 40),
                List.of(new Ingredient(600, 1)));
        var catalog = new MysticForgeCatalog(List.of(bow, promo), 0, "test");
        AtomicInteger itemCalls = new AtomicInteger(), quoteCalls = new AtomicInteger();
        var items = new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(Set<Integer> ids) {
                itemCalls.incrementAndGet();
                Map<Integer, ItemInfo> m = new HashMap<>();
                m.put(500, new ItemInfo(500, "Aurene's Wing", "https://i/x.png", 0, "Weapon", "LongBow"));
                m.put(600, new ItemInfo(600, "Gossamer Scrap", null, 0, "CraftingMaterial", null));
                return m;
            }
        };
        var tp = new TpPriceRepository() {
            @Override public Map<Integer, PriceQuote> loadTpQuotes(Set<Integer> ids) {
                quoteCalls.incrementAndGet();
                return Map.of(500, new PriceQuote(1000, 1500), 600, new PriceQuote(0, null));
            }
        };
        var controller = new MysticForgeApiController(null, null, () -> catalog, items, tp);

        var out = controller.recipes();

        assertEquals(1, itemCalls.get());
        assertEquals(1, quoteCalls.get());
        assertEquals(2, out.recipeCount());
        assertEquals(2, out.referenceCount());
        var wing = out.recipes().stream().filter(r -> r.recipeId() == -1).findFirst().orElseThrow();
        assertEquals("LongBow", wing.itemSubtype());
        assertEquals("WEAPONS", wing.category());
        assertEquals("FIXED", wing.outcome());
        assertEquals(1500, wing.sellUnitCopper());
        assertEquals("https://wiki.guildwars2.com/wiki/Aurene%27s_Wing", wing.wikiUrl());
        var scrap = out.recipes().stream().filter(r -> r.recipeId() == -2).findFirst().orElseThrow();
        assertEquals("EXPECTED", scrap.outcome());
        assertEquals("18.92", scrap.expectedMean());
        assertEquals("MATERIALS", scrap.category());
        assertNull(scrap.buyUnitCopper(), "a zero quote is unpriced, not free");
        assertNull(scrap.sellUnitCopper());
        var refs = out.recipes().stream().filter(r -> "REFERENCE_RANDOM".equals(r.outcome())).toList();
        assertEquals(2, refs.size());
        assertTrue(refs.stream().allMatch(r -> r.outputCount() == 0 && r.expectedMean() == null),
                "a random Clover row never claims a fixed or expected yield");
    }

    /** Validation happens before any account work: the (null) service is never reached for an invalid request. */
    @Test
    void invalidProjectRequestsAreRejectedBeforeAnyAccountWork() {
        var controller = new MysticForgeApiController(null, null, () -> new MysticForgeCatalog(List.of(), 0, "t"),
                new ItemRepository(), new TpPriceRepository());
        var http = new org.springframework.mock.web.MockHttpServletRequest();
        for (var bad : List.of(new web.dto.MysticForgeDtos.ProjectRequest(null, 1L),
                new web.dto.MysticForgeDtos.ProjectRequest(5, 1L),          // ordinary recipe id, not a Forge id
                new web.dto.MysticForgeDtos.ProjectRequest(-1, 0L),
                new web.dto.MysticForgeDtos.ProjectRequest(-1, 9_999_999_999L))) {
            assertThrows(ApiValidationException.class, () -> controller.project(bad, http), String.valueOf(bad));
        }
        assertThrows(ApiValidationException.class, () -> controller.project(null, http));
    }
}
