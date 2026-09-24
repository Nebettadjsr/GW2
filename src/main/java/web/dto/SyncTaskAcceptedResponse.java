package web.dto;

/**
 * Acceptance body for an asynchronous synchronization trigger (STORY-API-003).
 *
 * <p>Returned with HTTP 202: it states that the work was <em>accepted</em>, never that it finished.
 * It carries no result for exactly that reason — the outcome is only available from the status
 * resource this body points at.
 *
 * @param taskId    identifier to poll
 * @param operation operation the accepted task runs
 * @param statusUrl path of the status endpoint for {@code taskId}
 */
public record SyncTaskAcceptedResponse(String taskId, String operation, String statusUrl) {
}
