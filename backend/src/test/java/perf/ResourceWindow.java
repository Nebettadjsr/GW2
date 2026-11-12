package perf;

/**
 * Application-process resources observed over one stage window (units and limitations are part of the value).
 * CPU is the whole benchmark JVM: the application plus the in-process Java generator. Chromium, Node and
 * PostgreSQL are separate processes and are not included. Resident memory is legitimately unavailable where
 * the OS does not report it (for example Windows); other resources remain reported.
 */
record ResourceWindow(String stage, long elapsedMillis, long cpuNanos, int processors, long heapPeakBytes, long heapMaxBytes, long rssPeakBytes) {
    String describe() {
        String cpu = cpuNanos < 0 || elapsedMillis <= 0 ? "cpu unavailable"
                : String.format(java.util.Locale.ROOT, "cpu %.2f core-equivalents of %d logical cores (process CPU time / wall time)",
                        cpuNanos / 1e6 / elapsedMillis, processors);
        String heap = heapPeakBytes < 0 ? "heap unavailable"
                : "heap peak " + heapPeakBytes / (1 << 20) + " MiB (sampled, may miss short spikes)"
                + (heapMaxBytes > 0 ? " of limit " + heapMaxBytes / (1 << 20) + " MiB, headroom " + Math.max(0, heapMaxBytes - heapPeakBytes) / (1 << 20) + " MiB" : ", heap limit unavailable");
        String rss = rssPeakBytes < 0 ? "resident memory unavailable on this OS" : "resident peak " + rssPeakBytes / (1 << 20) + " MiB (benchmark JVM only)";
        return stage + " [" + elapsedMillis + " ms]: " + cpu + "; " + heap + "; " + rss;
    }
}
