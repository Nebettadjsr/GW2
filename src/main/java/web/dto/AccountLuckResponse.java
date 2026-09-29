package web.dto;

import java.time.Instant;
import java.util.List;

/** Compact read model for current-account Luck; thresholds are resolved on the backend. */
public record AccountLuckResponse(
        long consumedLuck,
        int currentLuckMagicFindPercent,
        long cumulativeLuckForCurrentPercent,
        Integer nextMagicFindPercent,
        Long cumulativeLuckForNextPercent,
        long luckRemainingToNextPercent,
        long luckRemainingToCap,
        long cumulativeLuckForCap,
        Instant fetchedAt,
        List<TargetDto> targets) {
    public record TargetDto(String kind, int magicFindPercent, long cumulativeLuck, long luckRemaining) {}
}
