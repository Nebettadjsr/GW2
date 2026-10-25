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
    // Public endpoint (no auth) -> JSON (strict 200/206)
    // -----------------------
    public static JsonNode getPublic(String url) throws IOException, InterruptedException {
        HttpRequest req = jsonGetRequest(url);
        return sendJsonStrict(req, url);
    }

    // -----------------------
    // Authenticated endpoint -> JSON (strict 200/206)
    // -----------------------
    public static JsonNode getAuth(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + AppConfig.API_KEY.trim())
                .GET()
                .build();

        return sendJsonStrict(req, url);
    }

    /**
     * Authenticated GET with a caller-supplied transient key (STORY-SYNC-006). Never reads the
     * deployment key; the key is used for this request only and appears in no message.
     */
    public static JsonNode getAuth(String url, String apiKey) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("no transient credential is available for this request");
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey.trim())
                .GET()
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        // Unlike sendJsonStrict, nothing of the response body is printed or carried into an
        // exception: only the request target and the status code, which classify the failure safely.
        if (!HttpStatus.isOkOrPartial(res.statusCode())) {
            throw new RuntimeException("GET " + url + " failed: HTTP " + res.statusCode());
        }
        try {
            return MAPPER.readTree(res.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Jackson's message quotes the offending payload text; drop it and the cause.
            throw new IOException("GET " + url + " returned malformed JSON");
        }
    }

    // -----------------------
    // "Raw response" helpers (caller decides policy)
    // -----------------------

    /** Public GET returning the raw String response (NO status enforcement here). */
    public static HttpResponse<String> getPublicResponse(String url) throws IOException, InterruptedException {
        HttpRequest req = jsonGetRequest(url);
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Public GET returning bytes (NO status enforcement here). */
    public static HttpResponse<byte[]> getBytesResponse(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "image/png,image/*;q=0.9,*/*;q=0.8")
                .GET()
                .build();

        return CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    // -----------------------
    // Batch logic
    // -----------------------
    public static JsonNode getPublicArray(String url) throws IOException, InterruptedException {
        JsonNode node = getPublic(url); // strict 200/206
        if (!node.isArray()) {
            throw new RuntimeException("Expected JSON array from url=" + url);
        }
        return node;
    }

    // -----------------------
    // JSON parsing helper
    // -----------------------
    public static JsonNode readJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new RuntimeException("Invalid JSON body", e);
        }
    }

    // -----------------------
    // Internal: build request + strict send
    // -----------------------
    private static HttpRequest jsonGetRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private static JsonNode sendJsonStrict(HttpRequest req, String url)
            throws IOException, InterruptedException {

        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        // Central rule for our "JSON convenience" methods:
        // If you call getPublic()/getAuth(), you only accept 200 or 206.
        HttpStatus.requireOkOrPartial(res.statusCode(), "GET " + url, res.body());

        return MAPPER.readTree(res.body());
    }
}