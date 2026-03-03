import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Gw2PriceFetch {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // pull JSON from API with ItemId endpoint
    public static Price getPrices(int itemId) {
        String url = "https://api.guildwars2.com/v2/commerce/prices?ids=" + itemId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode item = root.isArray() ? root.get(0) : root;

            int buyPrice = item.path("buys").path("unit_price").asInt(0);
            int sellPrice = item.path("sells").path("unit_price").asInt(0);

            return new Price(buyPrice, sellPrice);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return new Price(0, 0);
        }
    }
}
