package web;

import application.GlobalDataRefreshService;
import org.junit.jupiter.api.Test;
import web.task.BackgroundTaskService;
import web.task.TaskState;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalDataRefreshSchedulerTest {
    @Test
    void schedulerSubmitsTheSameServiceAndDuplicateWorkIsRefusedUntilItCompletes() throws Exception {
        try (var tasks = new BackgroundTaskService()) {
            var service = new BlockingRefreshService();
            var scheduler = new GlobalDataRefreshScheduler(service, tasks);

            scheduler.checkGlobalData();
            assertTrue(service.entered.await(5, TimeUnit.SECONDS));
            scheduler.checkGlobalData();
            assertEquals(1, service.calls.get());

            service.release.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && service.finished.getCount() != 0) Thread.sleep(10);
            assertEquals(0, service.finished.getCount());
            assertEquals(1, service.calls.get());
        }
    }

    private static final class BlockingRefreshService extends GlobalDataRefreshService {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);

        BlockingRefreshService() {
            super(new sync.GlobalDataRefreshGateway(), new application.CraftingGraphRebuildService(
                    new repo.CraftingGraphCache(new repo.RecipeRepository())));
        }

        @Override
        public RefreshResult refreshAll() throws Exception {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                return new RefreshResult(false, false, false);
            } finally {
                finished.countDown();
            }
        }
    }
}
