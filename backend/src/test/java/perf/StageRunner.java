package perf;

/** Runs finite measured windows against the isolated application. Implementations own all workers. */
public interface StageRunner {
    StageObservation run(int level, String role, long seed);

    /** Stop scheduling and wait (bounded) for protocol/browser workers and relevant application jobs. */
    boolean drain(long timeoutMillis);
}
