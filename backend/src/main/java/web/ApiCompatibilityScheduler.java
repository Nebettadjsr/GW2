package web;

import application.ApiCompatibilityService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the bounded public-API compatibility check automatically after startup and then every six hours,
 * through the same admission path as the owner-only manual action, so a duplicate is safely refused.
 */
@Component
public class ApiCompatibilityScheduler {
    private final ApiCompatibilityService service;

    public ApiCompatibilityScheduler(ApiCompatibilityService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${gw2.api-compatibility.interval-ms:21600000}",
            initialDelayString = "${gw2.api-compatibility.initial-delay-ms:60000}")
    public void checkApiCompatibility() {
        try {
            service.start();
        } catch (RuntimeException ignored) {
            // Includes duplicate admission and a full executor; the next scheduled run retries.
        }
    }
}
