package api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.Price;
import parser.TpPriceParser;
import util.TpPrice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Gw2PriceFetch {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Price getPrices(int itemId) {
        String url = "https://api.guildwars2.com/v2/commerce/prices/" + itemId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return new Price(0, 0);
            }

            JsonNode node = MAPPER.readTree(response.body());
            TpPrice  q    = TpPriceParser.parse(node);
            if (q == null || !q.hasMarketData()) {
                return new Price(0, 0);
            }

            // Price expects ints; quote uses Integer for null-safety
            return new Price(q.buyUnit(), q.sellUnit());

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return new Price(0, 0);
        }
    }
}