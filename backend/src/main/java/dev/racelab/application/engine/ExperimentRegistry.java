package dev.racelab.application.engine;

import dev.racelab.application.exception.ApiException;
import dev.racelab.domain.model.ScenarioDescriptor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every {@link Experiment} bean, indexed by scenario id. Adding a scenario = adding one bean. */
@Component
public class ExperimentRegistry {

    private final Map<String, Experiment> experiments = new LinkedHashMap<>();

    public ExperimentRegistry(List<Experiment> beans) {
        beans.stream().sorted(Comparator.comparing(Experiment::getType))
                .forEach(e -> experiments.put(e.getType(), e));
    }

    public Experiment get(String scenarioId) {
        Experiment e = experiments.get(scenarioId);
        if (e == null) {
            throw ApiException.notFound("Scenario '" + scenarioId + "'");
        }
        return e;
    }

    public List<ScenarioDescriptor> descriptors() {
        return experiments.values().stream().map(Experiment::descriptor).toList();
    }
}
