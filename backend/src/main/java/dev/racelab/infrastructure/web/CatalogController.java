package dev.racelab.infrastructure.web;

import dev.racelab.application.catalog.PresetCatalog;
import dev.racelab.application.engine.ExperimentRegistry;
import dev.racelab.application.engine.StrategyRegistry;
import dev.racelab.application.service.CodeExampleService;
import dev.racelab.domain.model.ExperimentPreset;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.StrategyDescriptor;
import dev.racelab.domain.model.StrategyType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Catalog", description = "Scenarios, experiment presets, strategies and the code that implements them")
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final ExperimentRegistry registry;
    private final PresetCatalog presets;
    private final StrategyRegistry strategies;
    private final CodeExampleService code;

    public CatalogController(ExperimentRegistry registry, PresetCatalog presets, StrategyRegistry strategies,
                             CodeExampleService code) {
        this.registry = registry;
        this.presets = presets;
        this.strategies = strategies;
        this.code = code;
    }

    @Operation(summary = "Executable business scenarios")
    @GetMapping("/scenarios")
    public List<ScenarioDescriptor> scenarios() {
        return registry.descriptors();
    }

    @GetMapping("/scenarios/{id}")
    public ScenarioDescriptor scenario(@PathVariable String id) {
        return registry.get(id).descriptor();
    }

    @Operation(summary = "Experiment catalog (presets) including the demo shortcuts")
    @GetMapping("/catalog")
    public List<ExperimentPreset> catalog() {
        return presets.all();
    }

    @GetMapping("/catalog/{id}")
    public ExperimentPreset preset(@PathVariable String id) {
        return presets.get(id);
    }

    @GetMapping("/strategies")
    public List<StrategyDescriptor> strategies() {
        return strategies.all();
    }

    @Operation(summary = "Code (Java/SQL) extracted from the implementation that actually runs the strategy")
    @GetMapping("/code-examples/{scenarioId}/{strategy}")
    public CodeExampleService.CodeExample codeExample(@PathVariable String scenarioId, @PathVariable StrategyType strategy) {
        return code.example(scenarioId, strategy);
    }
}
