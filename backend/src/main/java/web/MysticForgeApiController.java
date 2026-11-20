package web;

import application.CachedCraftingProfitService;
import application.CraftingProfitService;
import craft.ExpectedYield;
import craft.ForgeProjectPlanner;
import craft.ForgeReferenceRecipes;
import craft.MysticForgeCatalog;
import craft.PriceQuote;
import craft.Recipe;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import parser.MysticForgeCatalogLoader;
import repo.ItemRepository;
import repo.tp.TpPriceRepository;
import web.dto.CalculationFreshnessDto;
import web.dto.MysticForgeDtos.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Mystic Forge HTTP contract. {@code GET /api/mystic-forge/recipes} is a public, cheap catalogue: bundled recipe
 * metadata, cached item names/icons/types and cached output quotes - no planner, no inventory, no account read, no
 * upstream call. {@code POST /api/mystic-forge/project} plans exactly one selected target for the verified session's
 * account (see {@link CachedCraftingProfitService#forgeProject}); the account is never a body parameter.
 */
@RestController
@RequestMapping("/api/mystic-forge")
public class MysticForgeApiController {
    /** Upper bound on a requested target quantity/batch count (checked planning handles it without per-unit loops). */
    public static final long MAX_TARGET = 1_000_000_000L;
    static final int SPIRIT_SHARD = 23;

    private final CachedCraftingProfitService profitService;
    private final web.access.AccountAccessHttp accessHttp;
    private final Supplier<MysticForgeCatalog> catalog;
    private final ItemRepository items;
    private final TpPriceRepository tp;
    private final application.TradingPostRefreshCoordinator priceRefresh;

    @Autowired
    public MysticForgeApiController(CachedCraftingProfitService profitService, web.access.AccountAccessHttp accessHttp,
                                    application.TradingPostRefreshCoordinator priceRefresh) {
        this(profitService, accessHttp, MysticForgeCatalogLoader::bundled, new ItemRepository(), new TpPriceRepository(),
                priceRefresh);
    }

    MysticForgeApiController(CachedCraftingProfitService profitService, web.access.AccountAccessHttp accessHttp,
                             Supplier<MysticForgeCatalog> catalog, ItemRepository items, TpPriceRepository tp) {
        this(profitService, accessHttp, catalog, items, tp, null);
    }

    MysticForgeApiController(CachedCraftingProfitService profitService, web.access.AccountAccessHttp accessHttp,
                             Supplier<MysticForgeCatalog> catalog, ItemRepository items, TpPriceRepository tp,
                             application.TradingPostRefreshCoordinator priceRefresh) {
        this.priceRefresh = priceRefresh;
        this.profitService = profitService;
        this.accessHttp = accessHttp;
        this.catalog = catalog;
        this.items = items;
        this.tp = tp;
    }

    @GetMapping(path = "/recipes", produces = MediaType.APPLICATION_JSON_VALUE)
    public Catalogue recipes() throws Exception {
        MysticForgeCatalog c = catalog.get();
        Set<Integer> ids = new HashSet<>();
        for (Recipe r : c.recipes()) ids.add(r.outputItemId);
        for (var ref : ForgeReferenceRecipes.all()) ids.add(ref.outputItemId());
        Map<Integer, ItemRepository.ItemInfo> info = items.loadItems(ids);
        Map<Integer, PriceQuote> quotes = tp.loadCurrentTradeableQuotes(ids);
        List<RecipeRow> rows = new ArrayList<>();
        for (Recipe r : c.recipes()) {
            ExpectedYield y = r.expectedYield;
            MysticForgeCatalog.Provenance p = c.provenance(r.recipeId);
            rows.add(row(r.recipeId, r.outputItemId, y != null ? "EXPECTED" : "FIXED", r.outputCount,
                    y == null ? null : y.mean().toPlainString(), y == null ? null : y.max(), r.ingredients.size(),
                    p == null ? null : p.page(), p == null ? null : p.revision(), info, quotes));
        }
        for (var ref : ForgeReferenceRecipes.all()) {
            rows.add(row(ref.recipeId(), ref.outputItemId(), "REFERENCE_RANDOM", 0, null, null, ref.ingredients().size(),
                    ForgeReferenceRecipes.SOURCE_PAGE, ref.revision(), info, quotes));
        }
        // Cold catalogue: output items whose cached metadata is incomplete (no name/type, so no Weapon subtype to
        // search) are completed in the background on the shared single-flight run (public ids only, no account, no
        // planner). This request never waits for it; the client rereads while "completing" is true.
        boolean completing = false;
        if (priceRefresh != null) {
            // Same canonical completeness policy as IconSync/project path (name, type, rarity, icon, forge flag,
            // gear subtype). Short read, connection closed before admission; a failed read skips that demand only.
            Set<Integer> positive = new HashSet<>();
            for (int id : ids) if (id > 0) positive.add(id);
            Set<Integer> demand = new HashSet<>();
            try {
                demand.addAll(items.incompleteMetadataIds(positive));
            } catch (Exception metadataReadFailure) {
                // truthful: only quote demand (if any) is admitted
            }
            // Complete metadata is not a current quote: stale/missing tradeable quotes get the same scoped demand.
            try {
                demand.addAll(tp.staleOrMissingTradeable(positive));
            } catch (Exception quoteReadFailure) {
                // truthful: only metadata demand (if any) is admitted
            }
            if (!demand.isEmpty()) {
                try {
                    priceRefresh.requireItems(demand, null);
                    completing = true;
                } catch (RuntimeException admissionFailure) {
                    completing = false; // truthful: nothing was admitted; the cached catalogue is still served
                }
            }
        }
        return new Catalogue(c.status(), c.source(), c.license(), c.recipes().size(), ForgeReferenceRecipes.all().size(), rows,
                completing);
    }

    private static RecipeRow row(int recipeId, int out, String outcome, int count, String mean, Integer max, int kinds,
                                 String page, Long revision, Map<Integer, ItemRepository.ItemInfo> info,
                                 Map<Integer, PriceQuote> quotes) {
        ItemRepository.ItemInfo it = info.get(out);
        PriceQuote q = quotes.get(out);
        String type = it == null ? null : it.type;
        String category = "EXPECTED".equals(outcome) || "REFERENCE_RANDOM".equals(outcome) || "CraftingMaterial".equals(type)
                ? "MATERIALS"
                : "Weapon".equals(type) ? "WEAPONS" : "Armor".equals(type) ? "ARMOR" : "OTHER";
        return new RecipeRow(recipeId, out, it == null ? null : it.name, it == null ? null : application.icons.ItemIconUrls.iconUrlFor(out, it.iconUrl), type,
                it == null ? null : it.subtype, category, outcome, count, mean, max,
                q == null || q.buyUnit == null || q.buyUnit <= 0 ? null : q.buyUnit,
                q == null || q.sellUnit == null || q.sellUnit <= 0 ? null : q.sellUnit,
                page, revision, wiki(it == null ? null : it.name), kinds);
    }

    static String currencyWiki(int id, String name) {
        return name.startsWith("Currency ") ? null : wiki(id == SPIRIT_SHARD ? "Spirit Shard" : name);
    }

    static String wiki(String name) {
        return name == null || name.isBlank() ? null
                : "https://wiki.guildwars2.com/wiki/" + URLEncoder.encode(name.replace(' ', '_'), StandardCharsets.UTF_8);
    }

    @PostMapping(path = "/project", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Project> project(@RequestBody(required = false) ProjectRequest request,
                                           HttpServletRequest http) throws Exception {
        if (web.access.AccountAccessHttp.presentedApiKey(http) != null && !accessHttp.isSameOrigin(http)) {
            throw new ForeignOriginException();
        }
        if (request == null || request.recipeId() == null) throw new ApiValidationException("recipeId is required");
        long target = request.target() == null ? 1 : request.target();
        if (target < 1 || target > MAX_TARGET) throw new ApiValidationException("target must be between 1 and " + MAX_TARGET);
        int recipeId = request.recipeId();
        if (recipeId >= 0) throw new ApiValidationException("recipeId must identify a Mystic Forge recipe");

        craft.ForgeSettings settings = settings(request);
        String key = web.access.AccountAccessHttp.presentedApiKey(http);
        var keyed = key == null ? null : new CachedCraftingProfitService.KeyedAdmission(
                web.access.RequestAccountContext.required(http),
                web.access.AccountAccessHttp.presentedSessionId(http).orElseThrow(), key);
        var outcome = profitService.forgeProject(web.access.RequestAccountContext.scopeOf(http), recipeId, target, settings, keyed,
                web.access.RequestAccountContext.required(http));
        if (outcome.unknownRecipe()) throw new ApiValidationException("unknown Mystic Forge recipe");
        var freshness = CalculationFreshnessMapper.toDto(outcome.freshness());
        if (!outcome.hasResult()) {
            // No source ever synchronized: never an empty plan that reads as "nothing needed".
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Project(recipeId, null, null, null, target, 0, null, false, "account data not yet available",
                    false, 0, true, List.of(), null, List.of(), List.of(), null, null, null, null, freshness,
                    settingsDto(settings), null, null, 0, null));
        }
        return ResponseEntity.ok(toProject(outcome.data(), freshness));
    }

    static craft.ForgeSettings settings(ProjectRequest r) {
        var d = craft.ForgeSettings.DEFAULT;
        return new craft.ForgeSettings(
                enumOf(craft.ForgeSettings.PriceMode.class, r.priceMode(), d.priceMode(), "priceMode"),
                r.useOwn() == null ? d.useOwn() : r.useOwn(), r.valueOwn() == null ? d.valueOwn() : r.valueOwn(),
                enumOf(craft.ForgeSettings.DailyMode.class, r.daily(), d.daily(), "daily"),
                r.promotions() == null ? d.promotions() : r.promotions(),
                enumOf(craft.ForgeSettings.ReduceMode.class, r.reduce(), d.reduce(), "reduce"));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, E fallback, String field) {
        if (value == null) return fallback;
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException e) { throw new ApiValidationException(field + " is not a supported value"); }
    }

    static SettingsDto settingsDto(craft.ForgeSettings s) {
        return new SettingsDto(s.priceMode().name(), s.useOwn(), s.valueOwn(), s.daily().name(), s.promotions(), s.reduce().name());
    }

    static Project toProject(CraftingProfitService.ForgeProjectData d, CalculationFreshnessDto freshness) {
        ForgeProjectPlanner.Project p = d.project();
        Map<Integer, ItemRepository.ItemInfo> info = d.items();
        boolean random = d.referenceOnly() || d.batchTarget();
        List<ShoppingDto> shopping = new ArrayList<>();
        List<Item> unpriced = new ArrayList<>();
        boolean partialLine = false;
        for (var l : p.items()) {
            if (l.missing() <= 0) continue;
            Long known = null;
            if (l.knownUnitCopper() != null) {
                try { known = ForgeProjectPlanner.mul(l.knownUnitCopper(), l.missing()); }
                catch (ArithmeticException overflow) { known = null; partialLine = true; } // unavailable line total, never a fake exact cost
            }
            shopping.add(new ShoppingDto(item(l.itemId(), info), l.needed(), l.fromOwned(), l.missing(), l.knownUnitCopper(), known,
                    "TRADING_POST", 0, List.of(), null));
        }
        for (var v : p.vendorPurchases()) {
            List<CurrencyCostDto> costs = new ArrayList<>();
            v.currencies().forEach(c -> costs.add(new CurrencyCostDto(c.currencyId(), craft.KnownCurrencies.name(c.currencyId()), c.needed())));
            var line = p.items().stream().filter(l -> l.itemId() == v.itemId()).findFirst().orElse(null);
            // Cash of a vendor buy is real; a currency-only offer has no gold figure (null), never a free purchase.
            shopping.add(new ShoppingDto(item(v.itemId(), info), line == null ? v.units() : line.needed(),
                    line == null ? 0 : line.fromOwned(), v.units(), null, v.copper() > 0 ? v.copper() : null,
                    "VENDOR", v.purchases(), costs, v.hint()));
        }
        p.unpricedItemIds().forEach(id -> unpriced.add(item(id, info)));
        List<CurrencyDto> cur = new ArrayList<>();
        for (var c : p.currencies()) {
            String cname = craft.KnownCurrencies.name(c.currencyId());
            cur.add(new CurrencyDto(c.currencyId(), cname, CurrencyIcons.urlFor(c.currencyId()), currencyWiki(c.currencyId(), cname),
                    c.needed(), c.owned(), c.missing()));
        }
        SeedDto seed = p.seed() == null ? null : new SeedDto(p.seed().externalSeedUnits(), p.seed().reusedSeedUnits(),
                p.seed().grossGuaranteedOutput(), p.seed().netGuaranteedOutput(),
                p.seed().grossExpectedOutput().stripTrailingZeros().toPlainString(),
                p.seed().netExpectedOutput().stripTrailingZeros().toPlainString());
        EconomicsDto econ = null;
        if (p.economics() != null && !d.referenceOnly()) {
            var e = p.economics();
            List<Item> unpricedInputs = new ArrayList<>();
            e.unpricedInputItemIds().forEach(id -> unpricedInputs.add(item(id, info)));
            List<CurrencyDto> inputs = new ArrayList<>();
            for (var c : e.currencyInputs()) {
                String cname = craft.KnownCurrencies.name(c.currencyId());
                inputs.add(new CurrencyDto(c.currencyId(), cname, CurrencyIcons.urlFor(c.currencyId()),
                        currencyWiki(c.currencyId(), cname), c.needed(), null, null));
            }
            econ = new EconomicsDto(e.outputUnitSellCopper(), e.expectedGrossProceedsCopper(), e.feeCopper(),
                    e.expectedNetProceedsCopper(), e.knownShoppingCopper(), e.goldInputCopper(), inputs, unpricedInputs,
                    e.marginOnShoppingCopper(), e.marginCopper(), e.conditional(), e.fixedOutput());
        }
        NotesDto notes = null;
        if (p.notes() != null && !d.referenceOnly()) {
            var n = p.notes();
            notes = new NotesDto(n.needed(), n.walletKnown(), n.walletUsed(), n.shortfall(), n.containersNeeded(),
                    n.containersCrafted(), n.producedNotes(), n.surplus(), n.producerRecipeId(), n.estimateCopper(),
                    n.estimateIssue(), n.estimate().basisRecipeId(), CraftingRowMapper.variantDtos(n.estimate(), info),
                    n.ownedContainers(), n.ownedNotes(), n.acquireContainers(), n.uncoveredNotes(), n.producerLocked());
        }
        OwnValueDto ownValue = null;
        if (p.ownValue() != null) {
            List<Item> unknownOwn = new ArrayList<>();
            p.ownValue().unpricedItemIds().forEach(id -> unknownOwn.add(item(id, info)));
            ownValue = new OwnValueDto(p.ownValue().knownCopper(), unknownOwn);
        }
        CloverEstimateDto cloverDto = p.clover() == null ? null
                : new CloverEstimateDto(p.clover().missing(), p.clover().attempts(), p.clover().percent());
        String outcome = d.referenceOnly() ? "REFERENCE_RANDOM" : d.batchTarget() ? "EXPECTED" : "FIXED";
        String note = d.referenceOnly() ? "Random outcome: reference inputs only; no output, profit or completion is claimed."
                : null;
        ComparisonDto cmp = comparison(d, p, ownValue);
        // A whole-amount buy total beyond the exact range is decided here: the project is incomplete, never a guaranteed result.
        boolean cmpOverflow = cmp != null && cmp.outputBuyTotalCopper() == null && cmp.unitBuyCopper() != null;
        boolean complete = p.complete() && !cmpOverflow;
        String reason = cmpOverflow && p.complete() ? "quantity exceeds the supported range" : p.incompleteReason();
        Long guaranteed = d.referenceOnly() || cmpOverflow || p.guaranteedOutput() < 0 ? null : p.guaranteedOutput();
        return new Project(p.recipeId(), item(p.outputItemId(), info), outcome, random ? "BATCHES" : "OUTPUT_QUANTITY",
                d.requestedTarget(), p.batches(), guaranteed, complete, reason,
                d.walletKnown(), p.knownCopper(), !p.unpricedItemIds().isEmpty() || !complete || partialLine, unpriced, node(p.tree(), info, d.referenceOnly()), shopping,
                cur, note, d.referenceOnly() ? null : seed, econ, notes, freshness, settingsDto(p.settings()), ownValue, cloverDto,
                p.rootOwned(), cmp);
    }

    private static ComparisonDto comparison(CraftingProfitService.ForgeProjectData d, ForgeProjectPlanner.Project p, OwnValueDto own) {
        if (d.referenceOnly() || d.batchTarget()) return null; // random/batch outputs claim no purchasable output quantity
        PriceQuote q = d.tp().get(p.outputItemId());
        boolean orderMode = p.settings().priceMode() == craft.ForgeSettings.PriceMode.BUY_ORDER;
        Integer buy = q == null ? null : orderMode ? q.buyUnit : q.sellUnit;
        Long unitBuy = buy == null || buy <= 0 ? null : (long) buy;
        Long unitSell = q == null || q.buyUnit == null || q.buyUnit <= 0 ? null : (long) q.buyUnit;
        Long total = null, savings = null;
        boolean ownUnknown = own != null && !own.unpricedItems().isEmpty();
        boolean conditional = !p.currencies().isEmpty() || !p.unpricedItemIds().isEmpty() || ownUnknown || p.clover() != null || !p.complete();
        try {
            if (unitBuy != null) {
                total = ForgeProjectPlanner.mul(unitBuy, d.requestedTarget());
                if (p.complete() && p.unpricedItemIds().isEmpty() && !ownUnknown) {
                    savings = total - ForgeProjectPlanner.add(p.knownCopper(), own == null ? 0 : own.knownCopper());
                }
            }
        } catch (ArithmeticException overflow) { total = null; savings = null; }
        return new ComparisonDto(unitBuy, unitSell, total, savings, conditional, own == null);
    }

    private static NodeDto node(ForgeProjectPlanner.Node n, Map<Integer, ItemRepository.ItemInfo> info, boolean referenceRoot) {
        if (n == null) return null;
        List<NodeDto> kids = new ArrayList<>();
        n.children().forEach(c -> kids.add(node(c, info, false)));
        // A random reference root claims no output: its nominal quantity is neither needed, owned nor to craft.
        if (referenceRoot) {
            return new NodeDto(item(n.itemId(), info), 0, 0, 0, 0, 0,
                    n.recipeId(), n.source() == null ? null : n.source().name(), n.crafts(), n.minRating(), n.disciplines(),
                    n.cutoff(), kids, n.recipeKnown(), n.performable(), n.assignedCharacter(), n.hint(), 0, 0);
        }
        return new NodeDto(item(n.itemId(), info), n.needed(), n.fromOwned(), n.fromSurplus(), n.toCraft(), n.toAcquire(),
                n.recipeId(), n.source() == null ? null : n.source().name(), n.crafts(), n.minRating(), n.disciplines(),
                n.cutoff(), kids, n.recipeKnown(), n.performable(), n.assignedCharacter(), n.hint(), n.produced(), n.newSurplus());
    }

    private static Item item(int id, Map<Integer, ItemRepository.ItemInfo> info) {
        ItemRepository.ItemInfo it = info.get(id);
        // Same-origin icon route only; the retained ArenaNet source is never forwarded to the browser.
        return new Item(id, it == null ? null : it.name,
                it == null ? null : application.icons.ItemIconUrls.iconUrlFor(id, it.iconUrl), wiki(it == null ? null : it.name));
    }
}
