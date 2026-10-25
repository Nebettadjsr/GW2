package web;

import application.ItemReadService.PriceSourceUnavailableException;
import application.ItemReadService.MetadataSourceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import web.dto.ApiErrorResponse;

import java.sql.SQLException;

@RestControllerAdvice(assignableTypes = ItemReadApiController.class)
public class ItemReadApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ItemReadApiExceptionHandler.class);
    @ExceptionHandler(ApiValidationException.class)
    public ResponseEntity<ApiErrorResponse> invalid(ApiValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiErrorResponse> databaseUnavailable(SQLException error) {
        LOG.error("Item metadata database read failed", error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiErrorResponse(
                "DATA_STORE_UNAVAILABLE", "Item metadata is currently unavailable"));
    }

    @ExceptionHandler(PriceSourceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> upstreamUnavailable(PriceSourceUnavailableException error) {
        LOG.error("Live Trading Post price read failed", error);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiErrorResponse(
                "PRICE_SOURCE_UNAVAILABLE", "Live Trading Post prices are currently unavailable"));
    }

    @ExceptionHandler(MetadataSourceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> metadataUnavailable(MetadataSourceUnavailableException error) {
        LOG.error("GW2 item metadata acquisition failed", error);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiErrorResponse(
                "ITEM_METADATA_SOURCE_UNAVAILABLE", "GW2 item metadata is currently unavailable"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> readFailed(RuntimeException error) {
        LOG.error("Unexpected item read failure", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                "ITEM_READ_FAILED", "Item data could not be read"));
    }
}
