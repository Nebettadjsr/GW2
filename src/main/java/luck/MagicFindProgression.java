package luck;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Global Wiki progression data; no account state is held here. */
public final class MagicFindProgression {
    public static final int CAP_PERCENT = 300;
    private static final String RESOURCE = "/reference/luck_magic_find_thresholds.csv";
    private static final MagicFindProgression CANONICAL = loadCanonical();

    private final long[] thresholds;

    public static MagicFindProgression canonical() { return CANONICAL; }

    MagicFindProgression(long[] thresholds) {
        if (thresholds.length != CAP_PERCENT + 1 || thresholds[0] != 0) {
            throw new IllegalArgumentException("Expected thresholds for 0 through 300 percent");
        }
        for (int i = 1; i < thresholds.length; i++) {
            if (thresholds[i] <= thresholds[i - 1]) throw new IllegalArgumentException("Thresholds must increase");
        }
        this.thresholds = thresholds.clone();
    }

    public long thresholdFor(int percent) {
        if (percent < 0 || percent > CAP_PERCENT) throw new IllegalArgumentException("Magic Find target outside 0..300");
        return thresholds[percent];
    }

    public Progress resolve(long consumedLuck) {
        if (consumedLuck < 0) throw new IllegalArgumentException("Consumed Luck must be nonnegative");
        int index = Arrays.binarySearch(thresholds, consumedLuck);
        int current = index >= 0 ? index : -index - 2;
        long currentThreshold = thresholds[current];
        Long nextThreshold = current == CAP_PERCENT ? null : thresholds[current + 1];
        return new Progress(consumedLuck, current, currentThreshold, nextThreshold,
                nextThreshold == null ? 0 : nextThreshold - consumedLuck,
                Math.max(0, thresholds[CAP_PERCENT] - consumedLuck));
    }

    public long remainingFor(long consumedLuck, int targetPercent) {
        return Math.max(0, thresholdFor(targetPercent) - consumedLuck);
    }

    public record Progress(long consumedLuck, int magicFindPercent, long currentThreshold,
                           Long nextThreshold, long remainingToNext, long remainingToCap) {}

    private static MagicFindProgression loadCanonical() {
        long[] values = new long[CAP_PERCENT + 1];
        int expectedPercent = 0;
        try (var stream = MagicFindProgression.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing Luck progression resource");
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("magic_find_percent,")) continue;
                    String[] fields = line.split(",", -1);
                    if (fields.length != 2 || Integer.parseInt(fields[0]) != expectedPercent) {
                        throw new IllegalStateException("Luck progression has a missing or misplaced level");
                    }
                    values[expectedPercent++] = Long.parseLong(fields[1]);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load Luck progression", e);
        }
        if (expectedPercent != values.length) throw new IllegalStateException("Luck progression is incomplete");
        return new MagicFindProgression(values);
    }
}
