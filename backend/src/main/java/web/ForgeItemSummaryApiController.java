package web;

import application.BackgroundTasks;
import craft.PriceQuote;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import parser.MysticForgeCatalogLoader;
import repo.ItemSummaryRepository;
import repo.tp.TpPriceRepository;
import sync.EquipmentDetailsSync;

import java.time.Clock;
import java.util.Set;

/** Cache-only public header read; cold metadata is completed by the existing bounded background task facility. */
@RestController
@RequestMapping("/api/mystic-forge/items")
public final class ForgeItemSummaryApiController {
    private final BackgroundTasks tasks;
    private final ItemSummaryRepository summaries = new ItemSummaryRepository();
    private final TpPriceRepository prices = new TpPriceRepository();

    public ForgeItemSummaryApiController(BackgroundTasks tasks) { this.tasks = tasks; }

    public record SummaryResponse(ItemSummaryRepository.Summary item, Integer instantBuyCopper,
                                  Integer instantSellCopper, boolean completing) { }

    @GetMapping(path = "/{itemId}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public SummaryResponse summary(@PathVariable("itemId") int itemId) throws Exception {
        if (itemId <= 0 || (MysticForgeCatalogLoader.bundled().recipes().stream().noneMatch(r -> r.outputItemId == itemId)
                && craft.ForgeReferenceRecipes.all().stream().noneMatch(r -> r.outputItemId() == itemId))) {
            throw new ApiValidationException("itemId must identify a Mystic Forge output");
        }
        var item = summaries.find(itemId);
        boolean completing = item == null || !item.complete();
        if (completing) {
            try {
                tasks.submit("FORGE_ITEM_DETAILS", () -> {
                    sync.IconSync.completeMissingMetadata(Set.of(itemId));
                    var cached = summaries.find(itemId);
                    if (cached != null && cached.type() != null && Set.of("Weapon", "Armor", "Trinket", "Back").contains(cached.type())) {
                        EquipmentDetailsSync.repairSummaries(Set.of(itemId), EquipmentDetailsSync.LIVE, Clock.systemUTC());
                    }
                });
            } catch (BackgroundTasks.OperationAlreadyRunning active) {
                // The browser's bounded reread checks this cache again after the current public repair.
            }
        }
        PriceQuote quote = prices.loadCurrentTradeableQuotes(Set.of(itemId)).get(itemId);
        return new SummaryResponse(item, quote == null || quote.sellUnit == null || quote.sellUnit <= 0 ? null : quote.sellUnit,
                quote == null || quote.buyUnit == null || quote.buyUnit <= 0 ? null : quote.buyUnit, completing);
    }
}
