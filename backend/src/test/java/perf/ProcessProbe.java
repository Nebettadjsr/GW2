package perf;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Reads the application process's own memory and CPU time. The process is the JVM hosting the
 * application under measurement; reads that the platform cannot supply return {@code -1} and are
 * reported as unavailable rather than guessed.
 */
public final class ProcessProbe {

    private static final Path PROC_STATUS = Path.of("/proc/self/status");

    private ProcessProbe() {}

    public static int processors() {
        return Runtime.getRuntime().availableProcessors();
    }

    /** Process CPU time in nanoseconds, or -1 when the JVM cannot report it. */
    public static long cpuNanos() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getProcessCpuTime();
        }
        return -1;
    }

    public static long heapUsedBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** Resident set size in bytes from {@code /proc/self/status}, or -1 where that file does not exist. */
    public static long rssBytes() {
        return procStatusKb("VmRSS:");
    }

    /** Kernel-tracked peak resident set size, or -1 where unavailable. */
    public static long peakRssBytes() {
        return procStatusKb("VmHWM:");
    }

    private static long procStatusKb(String key) {
        try {
            return parseStatusKb(Files.readAllLines(PROC_STATUS), key);
        } catch (java.io.IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Parses a {@code Key:  1234 kB} line from /proc status text into bytes, or -1 when absent. */
    static long parseStatusKb(List<String> lines, String key) {
        for (String line : lines) {
            if (line.startsWith(key)) {
                String[] parts = line.substring(key.length()).trim().split("\\s+");
                return Long.parseLong(parts[0]) * 1024L;
            }
        }
        return -1;
    }

    /**
     * Polls a value at a fixed interval on a daemon thread and keeps the maximum seen. Sampling can
     * miss a spike shorter than the interval, so a peak is a lower bound on the true peak.
     */
    public static final class PeakSampler implements AutoCloseable {
        private final LongSupplier source;
        private final java.util.concurrent.atomic.AtomicLong peak = new java.util.concurrent.atomic.AtomicLong(-1);
        private volatile boolean running = true;
        private final Thread thread;

        public PeakSampler(String name, LongSupplier source, long intervalMillis) {
            this.source = source;
            observe();
            this.thread = new Thread(() -> {
                while (running) {
                    observe();
                    try {
                        Thread.sleep(intervalMillis);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "perf-sampler-" + name);
            thread.setDaemon(true);
            thread.start();
        }

        private void observe() {
            try {
                // called from the sampler and reader threads: keep the larger value atomically
                peak.accumulateAndGet(source.getAsLong(), Math::max);
            } catch (RuntimeException ignored) {
                // a failed sample is simply not a sample
            }
        }

        /** Peak observed so far, or -1 when no sample ever succeeded. */
        public long peak() {
            observe();
            return peak.get();
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
        }
    }
}
