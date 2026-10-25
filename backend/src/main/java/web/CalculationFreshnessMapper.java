package web;

import application.CalculationFreshness;
import web.dto.CalculationFreshnessDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies the application's freshness envelope into its transport form (STORY-APP-014).
 *
 * <p>Translation only. It makes no freshness judgement, reorders nothing, and derives no state: the
 * provisional/final decision, the per-source states and the coverage counts are all the ones
 * {@code application.CachedCraftingProfitService} decided from the captured inputs. The single
 * thing it adds is the status path for a task identifier, which is transport knowledge the
 * application layer deliberately does not hold.
 *
 * <p>Stateless, so it is safe to share across concurrent requests.
 */
final class CalculationFreshnessMapper {

    private CalculationFreshnessMapper() {}

    static CalculationFreshnessDto toDto(CalculationFreshness freshness) {
        if (freshness == null) return null;

        List<CalculationFreshnessDto.AccountSourceDto> sources = new ArrayList<>();
        for (CalculationFreshness.SourceFreshness source : freshness.accountSources()) {
            sources.add(new CalculationFreshnessDto.AccountSourceDto(
                    source.source(), text(source.fetchedAt()), source.state().name()));
        }

        List<CalculationFreshnessDto.OutstandingRefreshDto> outstanding = new ArrayList<>();
        for (CalculationFreshness.OutstandingRefresh work : freshness.outstandingWork()) {
            List<String> workSources = new ArrayList<>(work.sources());
            workSources.sort(String::compareTo);
            outstanding.add(new CalculationFreshnessDto.OutstandingRefreshDto(
                    work.kind().name(),
                    List.copyOf(workSources),
                    work.taskId(),
                    work.taskState(),
                    statusPath(work.taskId()),
                    work.reuse()));
        }

        List<CalculationFreshnessDto.RefreshFailureDto> failures = new ArrayList<>();
        for (CalculationFreshness.RefreshFailure failure : freshness.failures()) {
            failures.add(new CalculationFreshnessDto.RefreshFailureDto(
                    failure.kind().name(),
                    failure.taskId(),
                    statusPath(failure.taskId()),
                    failure.code(),
                    failure.message(),
                    failure.retryable()));
        }

        CalculationFreshness.PriceCoverage coverage = freshness.tradingPost();
        return new CalculationFreshnessDto(
                freshness.requestId(),
                freshness.calculationId(),
                text(freshness.evaluatedAt()),
                freshness.provisional(),
                freshness.resultAvailable(),
                List.copyOf(sources),
                new CalculationFreshnessDto.PriceCoverageDto(
                        coverage.requiredItems(),
                        coverage.freshItems(),
                        coverage.staleItems(),
                        coverage.missingItems(),
                        text(coverage.oldestFetchedAt()),
                        text(coverage.newestFetchedAt()),
                        coverage.covered()),
                List.copyOf(outstanding),
                List.copyOf(failures));
    }

    private static String statusPath(String taskId) {
        return taskId == null ? null : SyncTaskApiController.statusPath(taskId);
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
