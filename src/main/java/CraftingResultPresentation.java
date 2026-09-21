import craft.BlockedReason;
import craft.CraftResult;
import craft.CraftingSettings;
import craft.PriceQuote;

import java.util.Map;

/** Shared display state for the existing Profit and Discovery result rows. */
final class CraftingResultPresentation {
    final boolean calculationAvailable;
    final String status;

    CraftingResultPresentation(CraftResult result, Map<Integer, PriceQuote> quotes,
                               CraftingSettings settings) {
        boolean purchaseUnavailable = settings.allowBuying && result.missingToBuy.entrySet().stream()
                .anyMatch(entry -> {
                    if (entry.getValue() <= 0) return false;
                    var quote = quotes.get(entry.getKey());
                    Integer price = quote == null ? null : settings.listingBuy ? quote.buyUnit : quote.sellUnit;
                    return price == null || price <= 0;
                });
        calculationAvailable = result.craftableCount > 0 && result.revenueCopper > 0 && !purchaseUnavailable;
        BlockedReason reason = purchaseUnavailable ? BlockedReason.PRICE_UNAVAILABLE : result.blockedReason;
        String message = reason == BlockedReason.NONE ? "" :
                (result.craftableCount > 0 && !purchaseUnavailable ? "Further crafting blocked: " : "Blocked: ") + reason;
        if (result.revenueCopper <= 0) {
            message += (message.isEmpty() ? "" : "; ") + "Price unavailable (output)";
        }
        status = message.isEmpty() && !calculationAvailable ? "Unavailable: no completed craft" : message;
    }
}
