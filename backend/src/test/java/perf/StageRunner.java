package perf;

/** Runs finite measured windows against the isolated application. Implementations own all workers. */
public interface StageRunner {
    StageObservation run(int level, String role, long seed);

    /** Stop offering new work immediately (idempotent). Called on every exit path before {@link #drain}. */
    default void stopScheduling() { }

    /** Wait (bounded) for protocol/browser workers and relevant application jobs. */
    boolean drain(long timeoutMillis);
}
