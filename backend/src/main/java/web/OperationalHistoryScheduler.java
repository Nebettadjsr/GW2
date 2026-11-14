package web;

import application.ops.OperationalHistoryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Once a minute, moves closed in-memory minute buckets into the bounded history files. */
@Component
public class OperationalHistoryScheduler {
    private final OperationalHistoryService history;

    public OperationalHistoryScheduler(OperationalHistoryService history) { this.history = history; }

    @Scheduled(fixedRate = 60_000, initialDelay = 61_000)
    public void flush() { history.flush(); }
}
