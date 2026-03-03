package api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import repo.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class Gw2ApiClient {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Gw2ApiClient() {}

    // -----------------------
    // Public endpoint (no auth)
    // -----------------------
    public static JsonNode getPublic(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        return send(req, url);
    }

    // -----------------------
    // Authenticated endpoint
    // -----------------------
    public static JsonNode getAuth(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + AppConfig.API_KEY.trim())
                .GET()
                .build();

        return send(req, url);
    }

    // -----------------------
    // Internal send logic
    // -----------------------
    private static JsonNode send(HttpRequest req, String url)
            throws IOException, InterruptedException {

        HttpResponse<String> res =
                CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        int code = res.statusCode();

        if (code != 200 && code != 206) {
            throw new RuntimeException(
                    "GW2 API request failed: HTTP " + code + " url=" + url);
        }

        return MAPPER.readTree(res.body());
    }

    // -----------------------
    // Batch logic
    // -----------------------
    public static JsonNode getPublicArray(String url) throws IOException, InterruptedException {
        JsonNode node = getPublic(url);
        if (!node.isArray()) {
            throw new RuntimeException("Expected JSON array from url=" + url);
        }
        return node;
    }

    public static HttpResponse<String> getPublicResponse(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    public static JsonNode readJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new RuntimeException("Invalid JSON body", e);
        }
    }

    public static HttpResponse<byte[]> getBytesResponse(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "image/png,image/*;q=0.9,*/*;q=0.8")
                .GET()
                .build();

        return CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }
}