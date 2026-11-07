package web;

import application.icons.IconDelivery;
import application.icons.IconDeliveryResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Image of one build-reference resource (STORY-CHAR-009):
 * {@code GET /api/resources/{type}/{id}/icon/{sourceKey}.{ext}} with a closed set of types. Same response
 * contract as {@link ItemIconApiController} (it reuses its response mapping); no credential is needed
 * because these are public display assets.
 */
@RestController
@RequestMapping("/api/resources")
public class BuildResourceIconApiController {

    private final IconDelivery iconDelivery;

    public BuildResourceIconApiController(IconDelivery iconDelivery) {
        this.iconDelivery = iconDelivery;
    }

    @GetMapping("/{type}/{id}/icon/{fileName}")
    public ResponseEntity<?> icon(@PathVariable("type") String type, @PathVariable("id") String id,
                                  @PathVariable("fileName") String fileName,
                                  @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                                  String ifNoneMatch) {
        return switch (iconDelivery.deliverResource(type, id, fileName)) {
            case IconDeliveryResult.Delivered delivered -> ItemIconApiController.image(delivered, ifNoneMatch);
            case IconDeliveryResult.InvalidRequest(String reason) -> ItemIconApiController.failure(
                    HttpStatus.BAD_REQUEST, ItemIconApiController.ERROR_INVALID_REQUEST, reason, null);
            case IconDeliveryResult.NotFound ignored -> ItemIconApiController.failure(
                    HttpStatus.NOT_FOUND, ItemIconApiController.ERROR_NOT_FOUND, "No image is available for this resource", null);
            case IconDeliveryResult.Unavailable(String ignored, int retryAfterSeconds) -> ItemIconApiController.failure(
                    HttpStatus.SERVICE_UNAVAILABLE, ItemIconApiController.ERROR_UNAVAILABLE,
                    "The resource image is temporarily unavailable", retryAfterSeconds);
        };
    }
}
