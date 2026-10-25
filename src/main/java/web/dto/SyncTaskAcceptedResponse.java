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
 * @param admission how the demand was admitted (account sync only), e.g. {@code ADMITTED} or {@code WAITING_FOR_ACTIVE_TASK}
 * @param coversRequest false when {@code taskId} is an earlier, narrower run that does <em>not</em> refresh every
 *                      requested source: its success is not a successful sync of this request; retry afterwards
 * @param retryAfterSeconds suggested delay before repeating the request when {@code coversRequest} is false
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SyncTaskAcceptedResponse(String taskId, String operation, String statusUrl,
                                       String admission, Boolean coversRequest, Integer retryAfterSeconds) {

    public SyncTaskAcceptedResponse(String taskId, String operation, String statusUrl) {
        this(taskId, operation, statusUrl, null, null, null);
    }
}
