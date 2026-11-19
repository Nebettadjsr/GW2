package application;

import api.ApiCompatibilityChecker;
import api.ApiCompatibilityResult;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Owner API compatibility check (STORY-OPS-008): admission through the bounded administrative task
 * executor with in-flight coalescing (a second request is rejected), and a single in-process latest
 * result. Reading is cache-only and never touches ArenaNet. After a restart the result is truthfully
 * "not checked"; nothing is persisted.
 */
public class ApiCompatibilityService {

    public static final String OPERATION = "API_COMPATIBILITY_CHECK";
    /** A completed result older than this is shown as stale, never as a current success. */
    public static final Duration STALE_AFTER = Duration.ofHours(24);

    /** What the owner sees: the cached result plus server-computed staleness. */
    public record View(ApiCompatibilityResult result, boolean stale) {}

    private final BackgroundTasks tasks;
    private final Supplier<ApiCompatibilityChecker> checker;
    private final Clock clock;

    private ApiCompatibilityResult latest = ApiCompatibilityResult.notChecked();
    private boolean running;
    private String runningTaskId;

    public ApiCompatibilityService(BackgroundTasks tasks, Supplier<ApiCompatibilityChecker> checker, Clock clock) {
        this.tasks = tasks;
        this.checker = checker;
        this.clock = clock;
    }

    /** Admits one check; throws {@link BackgroundTasks.OperationAlreadyRunning} while one is in flight. */
    public synchronized void start() {
        if (running) throw new BackgroundTasks.OperationAlreadyRunning(OPERATION, runningTaskId);
        running = true;
        try {
            runningTaskId = tasks.submitAdministrative(OPERATION, this::run);
        } catch (RuntimeException e) {
            running = false;
            throw e;
        }
    }

    private void run() {
        ApiCompatibilityResult result;
        try {
            result = checker.get().check();
        } catch (RuntimeException | Error e) {
            // A checker defect must not look like success or leak, and an older green result must not
            // pass as the latest check: publish a sanitized unsuccessful attempt instead.
            synchronized (this) {
                latest = ApiCompatibilityResult.failedAttempt(clock.instant());
                running = false;
                runningTaskId = null;
            }
            if (e instanceof VirtualMachineError) throw e;
            // The shared task facility only records Exception; adapt an Error (without its text or
            // cause) so the task reaches FAILED and releases operation admission.
            if (e instanceof Error) throw new CheckFailedException();
            throw (RuntimeException) e;
        }
        synchronized (this) {
            latest = result;
            running = false;
            runningTaskId = null;
        }
    }

    /** Sanitized stand-in for a checker/supplier Error, which the task facility would not terminate. */
    static final class CheckFailedException extends RuntimeException {
        CheckFailedException() { super("API compatibility check failed", null, false, false); }
    }

    /** Cache-only read. */
    public synchronized View read() {
        var r = latest;
        boolean stale = r.state() == ApiCompatibilityResult.State.COMPLETE && r.checkedAt() != null
                && Duration.between(r.checkedAt(), clock.instant()).compareTo(STALE_AFTER) > 0;
        return new View(running ? r.asRunning() : r, stale);
    }
}
