package web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import web.dto.SyncTaskStatusResponse;
import web.task.BackgroundTaskService;
import web.task.TaskSnapshot;

/**
 * Status route for the background synchronization tasks (STORY-API-003, UD-007's "asynchronous
 * backend tasks with status reporting").
 *
 * <p>One route for every operation rather than one per trigger: the task facility is shared, the
 * status contract does not vary by operation, and the operation is reported in the body. A later
 * sync/refresh trigger therefore needs no status route of its own.
 *
 * <p>Thin: it looks the identifier up and maps what it finds. It starts nothing, cancels nothing and
 * waits for nothing.
 */
@RestController
@RequestMapping(SyncTaskApiController.TASKS_PATH)
public class SyncTaskApiController {

    static final String TASKS_PATH = "/api/sync/tasks";

    private final BackgroundTaskService taskService;

    public SyncTaskApiController(BackgroundTaskService taskService) {
        this.taskService = taskService;
    }

    /** Path of the status resource for {@code taskId}; the triggers hand this to the caller. */
    static String statusPath(String taskId) {
        return TASKS_PATH + "/" + taskId;
    }

    /**
     * Reports one task's lifecycle state.
     *
     * <p>Status contract: <b>200</b> for a known identifier, whatever its state — an unfinished,
     * a succeeded and a failed task are all a successful <em>lookup</em>, distinguished by the body's
     * {@code state}, never by the HTTP status. <b>404</b> {@code TASK_NOT_FOUND} for an identifier
     * this process cannot resolve (see {@link UnknownTaskException}).
     */
    @GetMapping(path = "/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SyncTaskStatusResponse status(@PathVariable("taskId") String taskId) {
        TaskSnapshot task = taskService.find(taskId)
                .orElseThrow(() -> new UnknownTaskException(taskId));

        return SyncTaskStatusMapper.toResponse(task);
    }
}
