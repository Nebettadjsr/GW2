package web;

import java.util.List;
import java.util.Map;

/**
 * The body-strictness rules the synchronization and refresh triggers share (STORY-API-003's
 * precedent, reused by STORY-API-004 and extended by STORY-API-005).
 *
 * <p>One rule underlies both methods below: <b>a field this route does not act on is refused, never
 * silently ignored</b>, so a caller that believes it is parameterising the work finds out. The
 * difference is only how many fields a route accepts — none for the two parameterless sync triggers
 * ({@link #rejectAnyRequestField}), exactly one for the price-refresh variant
 * ({@link #rejectUnknownRequestFields}). Kept here rather than copied into each controller so the
 * routes cannot drift into answering the same malformed request differently.
 *
 * <p>Both are raised before the trigger reaches {@code BackgroundTaskService.submit(...)}, so a
 * rejected request admits no task and synchronizes nothing.
 */
final class SyncRequestValidation {

    private SyncRequestValidation() {
    }

    /**
     * @param route   the route's own path, named in the message so the caller sees which trigger
     *                refused them
     * @param request the deserialized body, {@code null} when none was sent
     * @throws ApiValidationException if the body carries any field
     */
    static void rejectAnyRequestField(String route, Map<String, Object> request) {
        if (request != null && !request.isEmpty()) {
            throw new ApiValidationException(
                    "POST " + route + " accepts no request fields; send no body or an empty JSON object");
        }
    }

    /**
     * The same rule for a route that does accept one field: everything else is refused, whatever its
     * value. Says which fields were unexpected — a mistyped field name is the likeliest cause, and
     * naming it is more use than repeating the contract.
     *
     * @param route        the route's own path, named in the message so the caller sees which trigger
     *                     refused them
     * @param request      the deserialized body, {@code null} when none was sent
     * @param allowedField the one field this route reads
     * @throws ApiValidationException if the body carries any other field
     */
    static void rejectUnknownRequestFields(String route, Map<String, Object> request, String allowedField) {
        if (request == null) {
            return;
        }

        List<String> unexpected = request.keySet().stream()
                .filter(field -> !allowedField.equals(field))
                .sorted()
                .toList();

        if (!unexpected.isEmpty()) {
            throw new ApiValidationException("POST " + route + " accepts only the " + allowedField
                    + " field; unexpected: " + String.join(", ", unexpected));
        }
    }
}
