package web.dto;

/**
 * Uniform error body for every mapped failure of the crafting API (STORY-API-001,
 * TEST_STRATEGY.md §11).
 *
 * @param error   stable machine-readable code, safe to branch on
 * @param message human-readable explanation; carries caller-supplied detail only for request
 *                validation, never internal failure detail
 */
public record ApiErrorResponse(String error, String message) {
}
