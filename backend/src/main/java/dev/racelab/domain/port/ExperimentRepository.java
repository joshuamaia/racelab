package dev.racelab.domain.port;

import dev.racelab.domain.model.ExperimentRecord;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.ExperimentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExperimentRepository {

    void insert(ExperimentRecord record);

    Optional<ExperimentRecord> findById(String id);

    PageResult<ExperimentRecord> search(SearchFilter filter, int page, int size);

    List<ExperimentRecord> findByComparison(String comparisonId);

    void markStarted(String id, Instant startedAt);

    void markFinished(String id, ExperimentStatus status, Instant finishedAt, long durationMs, Boolean consistent,
                      int violationCount, int eventCount, String errorMessage);

    void saveResult(String id, ExperimentResult result);

    Optional<ExperimentResult> findResult(String id);

    /** Removes the experiment history and every business row in the experiment namespace. */
    void deleteWithData(String id);

    /** Marks experiments left RUNNING by a previous process of this instance as FAILED. */
    int failInterrupted(String instanceId, String reason);

    record SearchFilter(String category, String status, String scenarioId, String strategy, boolean includeChildren) {
    }

    record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }
}
