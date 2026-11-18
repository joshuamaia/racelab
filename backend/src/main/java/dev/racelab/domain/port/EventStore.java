package dev.racelab.domain.port;

import dev.racelab.domain.model.ExperimentEvent;

import java.util.List;

/** Durable event log, written independently of the business transactions of the experiments. */
public interface EventStore {

    /** Enqueues an event for asynchronous persistence (never blocks a worker transaction). */
    void append(ExperimentEvent event);

    /** Blocks until every event of the experiment enqueued so far has been written. */
    void flush(String experimentId);

    List<ExperimentEvent> findAfter(String experimentId, long afterSequence, int limit);

    List<ExperimentEvent> findAll(String experimentId);
}
