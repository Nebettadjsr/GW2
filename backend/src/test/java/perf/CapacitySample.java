package perf;

/**
 * One usable-completion observation. Browser samples measure initiation-to-rendered-usability;
 * protocol samples measure HTTP time. {@code startDelayMillis} is generator queue delay and is
 * kept separate from {@code durationMillis}. {@code firstUsableMillis} (nullable) is the earlier first-usable
 * (possibly saved/retained) observation; it never decides capacity and is separate from the final duration. Contains no account identifiers, keys or bodies.
 */
public record CapacitySample(String journey, String kind, int stageLevel, String role, int user,
                             long scheduledOffsetMillis, long startDelayMillis, long durationMillis,
                             boolean finalUsable, boolean correct, Long firstUsableMillis) {

    /** Without a first-usable observation (protocol samples, failures). */
    public CapacitySample(String journey, String kind, int stageLevel, String role, int user, long scheduledOffsetMillis,
                          long startDelayMillis, long durationMillis, boolean finalUsable, boolean correct) {
        this(journey, kind, stageLevel, role, user, scheduledOffsetMillis, startDelayMillis, durationMillis, finalUsable, correct, null);
    }


    public static final String BROWSER = "browser";
    public static final String PROTOCOL = "protocol";

    /** Exactly the threshold is NOT over; only strictly greater durations are. */
    public boolean overReference(long thresholdMillis) {
        return durationMillis > thresholdMillis;
    }
}
