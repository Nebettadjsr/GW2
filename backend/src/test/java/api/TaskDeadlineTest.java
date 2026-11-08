package api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TaskDeadlineTest {
    @Test
    void fixedDocumentedBounds() {
        assertEquals(Duration.ofSeconds(10), TaskDeadline.CONNECT_TIMEOUT);
        assertEquals(Duration.ofSeconds(30), TaskDeadline.REQUEST_TIMEOUT);
        assertEquals(Duration.ofMinutes(30), TaskDeadline.TASK_BOUND);
    }

    @Test
    void checkThrowsOnlyAfterTheBudgetAndRemainingShrinks() throws Exception {
        var clock = new AtomicLong();
        var d = TaskDeadline.after(Duration.ofSeconds(10), clock::get);
        d.check();
        clock.addAndGet(Duration.ofSeconds(4).toNanos());
        assertEquals(6000, d.remainingMillis());
        clock.addAndGet(Duration.ofSeconds(6).toNanos());
        assertEquals(0, d.remainingMillis());
        assertThrows(TaskDeadline.ExceededException.class, d::check);
    }

    @Test
    void interruptionIsCooperativeAndUnboundedNeverThrows() throws Exception {
        TaskDeadline.NONE.check();
        assertEquals(Long.MAX_VALUE, TaskDeadline.NONE.remainingMillis());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> TaskDeadline.start().check());
        } finally {
            Thread.interrupted();
        }
    }
}
