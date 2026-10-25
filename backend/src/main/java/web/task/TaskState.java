package web.task;

/**
 * The lifecycle states a {@link BackgroundTaskService} task can be observed in (STORY-API-003).
 *
 * <p>Deliberately distinguishes "not finished yet" from both outcomes, so a status reader can never
 * mistake accepted-or-running work for a completed synchronization.
 */
public enum TaskState {

    /** Admitted; the body has not started executing yet. */
    PENDING,

    /** The body is executing. */
    RUNNING,

    /** The body returned normally. */
    SUCCEEDED,

    /** The body threw; the cause is recorded on the task. */
    FAILED;

    /** @return true once the task has an outcome and its state can no longer change */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
