package dev.racelab.application.engine;

import dev.racelab.domain.model.TransactionOutcome;

import java.util.List;

/** The code executed by one worker thread. Returns one outcome per logical operation. */
@FunctionalInterface
public interface WorkerTask {

    List<TransactionOutcome> run(WorkerRuntime runtime) throws Exception;
}
