package web;

import application.icons.IconDelivery;
import application.icons.IconDeliveryResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.dto.ApiErrorResponse;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * HTTP boundary for one item image (STORY-API-009, TARGET_ARCHITECTURE.md §12.1):
 * {@code GET /api/items/{itemId}/icon/{sourceKey}.{ext}}, the URL the item-bearing reads emit.
 *
 * <p>Thin by construction: it hands the route's two values to {@link IconDelivery} and turns the one
 * outcome it gets back into a response. It opens no file, computes no path, reads no metadata, calls
 * nothing upstream and decides no caching rule of its own.
 *
 * <p>Deliberately not a file server: the only path shape it answers is this one, the key and extension
 * are validated before anything is accessed, no request value can name a file, a host or a URL, and
 * there is no listing or index response anywhere under the route.
 *
 * <p>Response contract (§12.1):
 * <ul>
 *   <li>200 with the stored bytes, the verified {@code Content-Type}, {@code nosniff},
 *       {@code Cache-Control: public, max-age=86400} and a strong {@code ETag}.</li>
 *   <li>304 for a matching {@code If-None-Match}, keeping {@code Cache-Control} and {@code ETag}.</li>
 *   <li>400 for a malformed route, 404 for an unknown image, 503 with a finite {@code Retry-After}
 *       for a temporary failure - all three with {@code Cache-Control: no-store}, so a failure is
 *       never cached by the browser.</li>
 * </ul>
 * No response carries a filesystem path, an upstream URL or a lower layer's exception text.
 */
@RestController
@RequestMapping("/api/items")
public class ItemIconApiController {

    /**
     * One day, deliberately finite and without {@code immutable}: a repaired image has to be able to
     * become visible after revalidation. It is an application choice, not an upstream guarantee.
     */
    static final Duration BROWSER_FRESHNESS = Duration.ofDays(1);

    static final String ERROR_INVALID_REQUEST = "ICON_REQUEST_INVALID";
    static final String ERROR_NOT_FOUND = "ICON_NOT_FOUND";
    static final String ERROR_UNAVAILABLE = "ICON_UNAVAILABLE";

    private final IconDelivery iconDelivery;

    public ItemIconApiController(IconDelivery iconDelivery) {
        this.iconDelivery = iconDelivery;
    }

    /**
     * @param itemId      the item the icon belongs to, taken as text so a malformed value is this
     *                    endpoint's own 400 rather than a framework conversion error
     * @param fileName    {@code {sourceKey}.{ext}} - the source-versioned key and its extension
     * @param ifNoneMatch the browser's cached validator, when it sent one
     */
    @GetMapping("/{itemId}/icon/{fileName}")
    public ResponseEntity<?> icon(@PathVariable("itemId") String itemId,
                                  @PathVariable("fileName") String fileName,
                                  @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                                  String ifNoneMatch) {

        return switch (iconDelivery.deliver(itemId, fileName)) {
            case IconDeliveryResult.Delivered delivered -> image(delivered, ifNoneMatch);
            case IconDeliveryResult.InvalidRequest(String reason) ->
                    failure(HttpStatus.BAD_REQUEST, ERROR_INVALID_REQUEST, reason, null);
            case IconDeliveryResult.NotFound ignored ->
                    failure(HttpStatus.NOT_FOUND, ERROR_NOT_FOUND, "No image is available for this item", null);
            case IconDeliveryResult.Unavailable(String ignored, int retryAfterSeconds) ->
                    failure(HttpStatus.SERVICE_UNAVAILABLE, ERROR_UNAVAILABLE,
                            "The item image is temporarily unavailable", retryAfterSeconds);
        };
    }

    private static ResponseEntity<?> image(IconDeliveryResult.Delivered delivered, String ifNoneMatch) {
        CacheControl freshness = CacheControl.maxAge(BROWSER_FRESHNESS.toSeconds(), TimeUnit.SECONDS)
                .cachePublic();

        if (matches(ifNoneMatch, delivered.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(freshness)
                    .eTag(delivered.etag())
                    .build();
        }

        return ResponseEntity.ok()
                .cacheControl(freshness)
                .eTag(delivered.etag())
                .contentType(MediaType.parseMediaType(delivered.contentType()))
                .contentLength(delivered.bytes().length)
                .header("X-Content-Type-Options", "nosniff")
                .body(delivered.bytes());
    }

    /**
     * Strong-validator comparison over a possibly multi-valued header. {@code W/} is accepted as a
     * prefix a browser may have added; {@code *} matches any existing representation, as HTTP requires.
     */
    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;

        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed)) return true;
            if (trimmed.startsWith("W/")) trimmed = trimmed.substring(2);
            if (etag.equals(trimmed)) return true;
        }
        return false;
    }

    /**
     * Every non-success response: a fixed wording, no upstream detail, and {@code no-store} so a
     * temporary failure cannot be remembered by the browser as this image's answer.
     */
    private static ResponseEntity<?> failure(HttpStatus status,
                                             String error,
                                             String message,
                                             Integer retryAfterSeconds) {

        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON);

        if (retryAfterSeconds != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }

        return response.body(new ApiErrorResponse(error, message));
    }
}
