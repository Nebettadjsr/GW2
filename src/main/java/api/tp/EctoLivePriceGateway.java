package api.tp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.PriceQuote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Live, unsynchronized GW2 Trading Post price lookup for the Ectoplasm Salvage use case
 * (STORY-APP-003): a direct call to {@code /v2/commerce/prices}, distinct from the DB-backed
 * {@code repo.tp.TpPriceRepository} the Crafting flows use. Moved out of {@code EctoView}
 * unchanged so {@code application.EctoSalvageService} depends on this seam instead of the view
 * performing its own HTTP/JSON handling; a fake subclass substitutes for it in application-layer
 * tests (TARGET_ARCHITECTURE.md §25).
 */
public class EctoLivePriceGateway {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Map<Integer, PriceQuote> fetchQuotes(int... itemIds) throws Exception {
        String idsParam = Arrays.stream(itemIds)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        String url = "https://api.guildwars2.com/v2/commerce/prices?ids=" + idsParam;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200 && res.statusCode() != 206) {
            throw new RuntimeException("TP price fetch failed: HTTP " + res.statusCode());
        }

        JsonNode root = MAPPER.readTree(res.body());
        if (!root.isArray()) throw new RuntimeException("Unexpected TP JSON");

        Map<Integer, PriceQuote> out = new HashMap<>();
        for (JsonNode p : root) {
            int id = p.get("id").asInt();
            JsonNode buys = p.get("buys");
            JsonNode sells = p.get("sells");

            if (buys == null || sells == null || buys.isNull() || sells.isNull()) continue;

            int buyUnit = buys.get("unit_price").asInt();
            int sellUnit = sells.get("unit_price").asInt();

            out.put(id, new PriceQuote(buyUnit, sellUnit));
        }
        return out;
    }
}
