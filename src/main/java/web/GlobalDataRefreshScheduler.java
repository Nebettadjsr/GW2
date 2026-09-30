package web;

import application.GlobalDataRefreshService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import web.task.BackgroundTaskService;

/** Periodically submits the same bounded global refresh use case exposed to administrators. */
@Component
public class GlobalDataRefreshScheduler {
    static final String OPERATION = "GLOBAL_SYNC";
    private final GlobalDataRefreshService refreshService;
    private final BackgroundTaskService tasks;

    public GlobalDataRefreshScheduler(GlobalDataRefreshService refreshService, BackgroundTaskService tasks) {
        this.refreshService = refreshService;
        this.tasks = tasks;
    }

    @Scheduled(fixedDelayString = "${gw2.global-refresh.interval-ms:21600000}",
            initialDelayString = "${gw2.global-refresh.initial-delay-ms:60000}")
    public void checkGlobalData() {
        try {
            tasks.submit(OPERATION, refreshService::refreshAll);
        } catch (RuntimeException ignored) {
            // Includes duplicate admission: a manual or scheduled run is already doing this work.
            // A later fixed-delay execution retries after completion.
        }
    }
}
