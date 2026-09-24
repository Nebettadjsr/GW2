package web.task;

import java.time.Instant;

/**
 * Immutable point-in-time view of one background task (STORY-API-003), copied out of
 * {@link BackgroundTaskService} under its lock so a reader never observes a half-updated task.
 *
 * @param id          server-generated identifier
 * @param operation   operation key the task was submitted under
 * @param state       lifecycle state at the moment the snapshot was taken
 * @param submittedAt when the task was admitted
 * @param startedAt   when the body started executing; null while {@link TaskState#PENDING}
 * @param finishedAt  when the body finished; null until {@link TaskState#isTerminal()}
 * @param failure     the exception the body threw; null unless the state is {@link TaskState#FAILED}
 */
public record TaskSnapshot(String id,
                           String operation,
                           TaskState state,
                           Instant submittedAt,
                           Instant startedAt,
                           Instant finishedAt,
                           Exception failure) {
}
