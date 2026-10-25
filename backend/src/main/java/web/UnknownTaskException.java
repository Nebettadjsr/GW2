package web;

/**
 * A task identifier this process cannot resolve (STORY-API-003): never issued, evicted from the task
 * facility's bounded retention, or lost with a previous process. {@link SyncApiExceptionHandler} maps
 * it to HTTP 404; the three causes are deliberately indistinguishable to the caller, because the
 * in-memory facility cannot tell them apart either.
 *
 * <p>The identifier is in the message for the server log only — the handler answers with a fixed
 * message rather than echoing caller-supplied text.
 */
public class UnknownTaskException extends RuntimeException {

    public UnknownTaskException(String taskId) {
        super("Unknown task " + taskId);
    }
}
