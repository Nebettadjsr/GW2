package application.ops;

/** The only account information a history bucket carries: a state and a count, never an identity. */
public record ForegroundAccounts(State state, Integer exactCount, Integer lowerBound) {
    public enum State { COMPLETE, PARTIAL_STARTUP, TRUNCATED, UNAVAILABLE }

    public static ForegroundAccounts unavailable() { return new ForegroundAccounts(State.UNAVAILABLE, null, null); }

    /** The best single number to chart: the exact count, else the lower bound, else none. */
    public Integer reported() { return exactCount != null ? exactCount : lowerBound; }
}
