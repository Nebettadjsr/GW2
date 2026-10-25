package api.tp;

import api.BatchUtils;
import api.Gw2ApiClient;
import api.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TpPriceApi {

    private TpPriceApi() {}

    public record BatchResult(
            int statusCode,
            JsonNode array,          // only set for 200/206
            Set<Integer> returnedIds // only set for 200/206
    ) {}

    // ---- Public API ----

    /** Fetch /v2/commerce/prices?ids=... and return status + parsed array for 200/206 (404 means retry single) */
    public static BatchResult fetchBatch(List<Integer> batch) throws IOException, InterruptedException {
        String idsParam = BatchUtils.idsParam(batch);
        if (idsParam.isBlank()) return new BatchResult(200, null, Set.of());

        String url = "https://api.guildwars2.com/v2/commerce/prices?ids=" + idsParam;

        HttpResponse<String> res = getWithRetry429(url);
        int code = res.statusCode();

        // Centralized status policy for TP batch
        requireTpBatchStatus(code, url, res.body());

        // 404 => caller will retry individually
        if (code == 404) return new BatchResult(404, null, Set.of());

        JsonNode root = Gw2ApiClient.readJson(res.body());
        if (root == null || !root.isArray()) {
            throw new RuntimeException("TP batch unexpected JSON (not array). url=" + url);
        }

        Set<Integer> returned = new HashSet<>();
        for (JsonNode p : root) {
            if (p != null && p.hasNonNull("id")) {
                returned.add(p.get("id").asInt());
            }
        }

        return new BatchResult(code, root, returned);
    }

    /** Fetch /v2/commerce/prices/<id> and return parsed JSON object, or null if 404 */
    public static JsonNode fetchSingle(int itemId) throws IOException, InterruptedException {
        String url = "https://api.guildwars2.com/v2/commerce/prices/" + itemId;

        HttpResponse<String> res = getWithRetry429(url);
        int code = res.statusCode();

        // Centralized status policy for TP single
        requireTpSingleStatus(code, url, res.body());

        if (code == 404) return null; // item not tradeable / invalid id
        return Gw2ApiClient.readJson(res.body());
    }

    // ---- Centralized status policy (429 retry/backoff) ----

    private static HttpResponse<String> getWithRetry429(String url) throws IOException, InterruptedException {
        final int maxRetries = 3;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpResponse<String> res = Gw2ApiClient.getPublicResponse(url);
            int code = res.statusCode();

            if (code != 429) return res;
            HttpStatus.logWarn(429, "TP rate limit hit for " + url);
            // 429: honor Retry-After if present, else exponential backoff
            long sleepMs = retryAfterMs(res, attempt);
            Thread.sleep(sleepMs);
        }

        // last attempt
        return Gw2ApiClient.getPublicResponse(url);
    }

    private static long retryAfterMs(HttpResponse<String> res, int attempt) {
        // Retry-After is usually seconds
        String ra = res.headers().firstValue("Retry-After").orElse(null);
        if (ra != null) {
            try {
                long seconds = Long.parseLong(ra.trim());
                if (seconds > 0) return seconds * 1000L;
            } catch (NumberFormatException ignored) {}
        }

        // fallback exponential: 1s, 2s, 4s, 8s...
        return (long) Math.pow(2, attempt) * 1000L;
    }

    // ---- TP-specific status rules ----

    /** TP batch endpoint: 200/206 OK, 404 means "retry individually", everything else is error */
    private static void requireTpBatchStatus(int code, String url, String body) {
        if (code == 200 || code == 206 || code == 404) return;
        throw new RuntimeException("TP batch fetch failed: HTTP " + code + " url=" + url + " body=" + body);
    }

    /** TP single endpoint: 200 OK, 404 means "not found/not tradeable", everything else is error */
    private static void requireTpSingleStatus(int code, String url, String body) {
        if (code == 200 || code == 404) return;
        throw new RuntimeException("TP single fetch failed: HTTP " + code + " url=" + url + " body=" + body);
    }

    public static Set<Integer> fetchAllTradeableItemIds() throws IOException, InterruptedException {
        String url = "https://api.guildwars2.com/v2/commerce/prices";

        HttpResponse<String> res = getWithRetry429(url);
        int code = res.statusCode();
        if (code != 200) {
            throw new RuntimeException("TP prices id-list fetch failed: HTTP " + code + " url=" + url);
        }

        JsonNode root = Gw2ApiClient.readJson(res.body());
        if (root == null || !root.isArray()) {
            throw new RuntimeException("TP prices id-list unexpected JSON (not array). url=" + url);
        }

        Set<Integer> out = new HashSet<>();
        for (JsonNode n : root) {
            if (n != null && n.isInt()) {
                out.add(n.asInt());
            } else if (n != null && n.isObject() && n.hasNonNull("id")) {
                // safety fallback (in case API ever returns objects)
                out.add(n.get("id").asInt());
            }
        }
        return out;
    }
}