package dev.racelab.application.engine;

import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.ExperimentStatus;

/** A consumer of the live event stream of one experiment (e.g. an SSE connection). Must never block. */
public interface EventSubscriber {

    void onEvent(ExperimentEvent event);

    void onEnd(ExperimentStatus status);
}
