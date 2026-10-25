package web.dto;

/**
 * Status body for one background synchronization task (STORY-API-003).
 *
 * <p>{@code state} is the single field a caller branches on; the timestamps and {@code failure} are
 * the evidence behind it. A task that has not finished has no {@code finishedAt} and no
 * {@code failure}, and a failed task has a {@code failure} and no successful outcome anywhere in the
 * body — the two are never both readable as "done".
 *
 * @param taskId      the queried identifier
 * @param operation   operation the task runs
 * @param state       {@code PENDING} | {@code RUNNING} | {@code SUCCEEDED} | {@code FAILED}
 * @param submittedAt ISO-8601 UTC instant the task was accepted
 * @param startedAt   ISO-8601 UTC instant execution began; null while {@code PENDING}
 * @param finishedAt  ISO-8601 UTC instant execution ended; null unless {@code SUCCEEDED}/{@code FAILED}
 * @param failure     failure code and message; null unless {@code FAILED}
 */
public record SyncTaskStatusResponse(String taskId,
                                     String operation,
                                     String state,
                                     String submittedAt,
                                     String startedAt,
                                     String finishedAt,
                                     ApiErrorResponse failure) {
}
