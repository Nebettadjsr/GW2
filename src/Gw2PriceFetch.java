import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Gw2PriceFetch {

    // pull JSON from API with ItemId endpoint
    public static Price getPrices(int itemId) {
        String url = "https://api.guildwars2.com/v2/commerce/prices?ids=" + itemId;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();

            int buyPrice = extractUnitPrice(body, "buys");
            int sellPrice = extractUnitPrice(body, "sells");

            return new Price(buyPrice, sellPrice);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        return new Price(0, 0); // fallback
    }

    //Parse JSON for value
    public static int extractUnitPrice(String json, String section) {
        int sectionIndex = json.indexOf("\"" + section + "\"");
        int priceIndex = json.indexOf("unit_price", sectionIndex);
        int valueStart = json.indexOf(":", priceIndex) + 1;

        int commaIndex = json.indexOf(",", valueStart);
        int braceIndex = json.indexOf("}", valueStart);
        int valueEnd = (commaIndex == -1) ? braceIndex : Math.min(commaIndex, braceIndex);

        return Integer.parseInt(json.substring(valueStart, valueEnd).trim());
    }

}
