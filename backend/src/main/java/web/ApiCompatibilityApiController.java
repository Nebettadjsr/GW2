package web;

import application.ApiCompatibilityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.access.AccountAccessHttp;
import web.access.OwnerAccess;
import web.dto.ApiErrorResponse;

/**
 * Owner-only public-API compatibility check (STORY-OPS-008). Owner authorization precedes the Origin
 * check, admission and any read; GET returns the cached result only and never calls ArenaNet.
 */
@RestController
@RequestMapping("/api/system/api-compatibility")
public class ApiCompatibilityApiController {

    private final ApiCompatibilityService service;
    private final OwnerAccess ownerAccess;
    private final AccountAccessHttp http;

    public ApiCompatibilityApiController(ApiCompatibilityService service, OwnerAccess ownerAccess, AccountAccessHttp http) {
        this.service = service;
        this.ownerAccess = ownerAccess;
        this.http = http;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> read(HttpServletRequest request) {
        ResponseEntity<?> denial = ownerAccess.deny(request);
        if (denial != null) return denial;
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.read());
    }

    @PostMapping(path = "/check", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> check(HttpServletRequest request) {
        ResponseEntity<?> denial = ownerAccess.deny(request);
        if (denial != null) return denial;
        if (http != null && !http.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new ApiErrorResponse("ORIGIN_REJECTED",
                            "This request did not come from this application's own origin"));
        }
        service.start(); // a duplicate in-flight check raises OperationAlreadyRunning -> 409
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.read());
    }
}
