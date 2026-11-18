package dev.racelab.application.service;

import dev.racelab.application.catalog.PresetCatalog;
import dev.racelab.application.engine.ExperimentExecutor;
import dev.racelab.application.engine.ExperimentRegistry;
import dev.racelab.application.exception.ApiException;
import dev.racelab.application.port.MessageBroker;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.ExperimentPreset;
import dev.racelab.domain.model.ExperimentRecord;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.ExperimentStatus;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.port.EventStore;
import dev.racelab.domain.port.ExperimentRepository;
import dev.racelab.application.engine.ExperimentEventPublisher;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Use cases of the experiment lifecycle. */
@Service
public class ExperimentService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExperimentRegistry registry;
    private final PresetCatalog presets;
    private final ExperimentRepository repository;
    private final ExperimentExecutor executor;
    private final EventStore eventStore;
    private final ExperimentEventPublisher publisher;
    private final MessageBroker broker;
    private final Validator validator;
    private final Clock clock;
    private final String instanceId;

    public ExperimentService(ExperimentRegistry registry, PresetCatalog presets, ExperimentRepository repository,
                             ExperimentExecutor executor, EventStore eventStore, ExperimentEventPublisher publisher,
                             MessageBroker broker, Validator validator, Clock clock,
                             @Value("${racelab.instance-id}") String instanceId) {
        this.registry = registry;
        this.presets = presets;
        this.repository = repository;
        this.executor = executor;
        this.eventStore = eventStore;
        this.publisher = publisher;
        this.broker = broker;
        this.validator = validator;
        this.clock = clock;
        this.instanceId = instanceId;
    }

    public record CreateCommand(String presetId, String scenarioId, StrategyType strategy, String name,
                                ExperimentConfiguration configuration, boolean autoStart, String correlationId,
                                String comparisonId, boolean warmup, String rerunOf) {
    }

    public ExperimentView create(CreateCommand cmd) {
        ExperimentPreset preset = cmd.presetId() == null || cmd.presetId().isBlank() ? null : presets.get(cmd.presetId());
        String scenarioId = cmd.scenarioId() != null ? cmd.scenarioId() : preset != null ? preset.scenarioId() : null;
        if (scenarioId == null) {
            throw ApiException.validation("scenarioId or presetId is required");
        }
        if (preset != null && !preset.scenarioId().equals(scenarioId)) {
            throw ApiException.validation("Preset " + preset.id() + " belongs to scenario " + preset.scenarioId());
        }
        ScenarioDescriptor scenario = registry.get(scenarioId).descriptor();
        StrategyType strategy = cmd.strategy() != null ? cmd.strategy()
                : preset != null ? preset.strategy() : scenario.defaultStrategy();
        if (!scenario.supports(strategy)) {
            throw new ApiException(400, "UNSUPPORTED_STRATEGY",
                    "Scenario " + scenarioId + " supports " + scenario.supportedStrategies() + ", not " + strategy);
        }
        ExperimentConfiguration base = preset != null ? preset.configuration() : scenario.defaults();
        ExperimentConfiguration cfg = (cmd.configuration() == null ? ExperimentConfiguration.empty() : cmd.configuration())
                .withDefaults(base.withDefaults(scenario.defaults()));
        validate(cfg);
        if (cfg.stepByStep() && !scenario.supportsStepMode()) {
            throw ApiException.validation("Scenario " + scenarioId + " does not support step-by-step execution");
        }
        String name = cmd.name() != null && !cmd.name().isBlank() ? cmd.name().trim()
                : (preset != null ? preset.name() : scenario.name()) + " · " + strategy;
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        ExperimentRecord record = new ExperimentRecord(newId("EXP"), name, preset == null ? null : preset.id(),
                scenarioId, preset != null ? preset.category() : scenario.category(), strategy, ExperimentStatus.CREATED,
                cfg.executionMode(), instanceId, cmd.comparisonId(), cmd.warmup(), cmd.rerunOf(), cmd.correlationId(),
                clock.instant(), null, null, null, null, 0, 0, null, cfg);
        executor.create(record);
        if (cmd.autoStart()) {
            executor.start(record);
        }
        return view(record.id());
    }

    public ExperimentView start(String id) {
        ExperimentRecord record = record(id);
        executor.start(record);
        return view(id);
    }

    public ExperimentView step(String id, String workerId) {
        executor.step(id, workerId);
        return view(id);
    }

    public ExperimentView resume(String id) {
        executor.resume(id);
        return view(id);
    }

    public ExperimentView cancel(String id) {
        ExperimentRecord r = record(id);
        if (r.status().isTerminal()) {
            throw ApiException.invalidState("Experiment " + id + " already finished (" + r.status() + ")");
        }
        executor.cancel(id, "Cancelled by the user");
        return view(id);
    }

    public ExperimentView rerun(String id, boolean autoStart, String correlationId) {
        ExperimentRecord r = record(id);
        return create(new CreateCommand(r.presetId(), r.scenarioId(), r.strategy(), r.name(), r.configuration(),
                autoStart, correlationId, null, false, r.id()));
    }

    public void delete(String id) {
        ExperimentRecord r = record(id);
        if (r.status() == ExperimentStatus.RUNNING && executor.context(id).isPresent()) {
            throw ApiException.invalidState("Cancel the experiment before deleting it");
        }
        repository.deleteWithData(id);
        publisher.discard(id);
        broker.deleteTopic("racelab:outbox:" + id);
    }

    public ExperimentView view(String id) {
        return ExperimentView.of(record(id), executor.stepState(id).orElse(null));
    }

    public ExperimentRepository.PageResult<ExperimentView> search(ExperimentRepository.SearchFilter filter, int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw ApiException.validation("page must be >= 0 and size between 1 and 200");
        }
        var p = repository.search(filter, page, size);
        return new ExperimentRepository.PageResult<>(p.content().stream()
                .map(r -> ExperimentView.of(r, executor.stepState(r.id()).orElse(null))).toList(),
                p.page(), p.size(), p.totalElements(), p.totalPages());
    }

    public ExperimentRecord record(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Experiment " + id));
    }

    public ExperimentResult result(String id) {
        record(id);
        return repository.findResult(id).orElseThrow(() ->
                new ApiException(404, "RESULT_NOT_AVAILABLE", "The result of " + id + " is not available yet"));
    }

    public List<ExperimentEvent> events(String id) {
        record(id);
        return publisher.bus(id).map(b -> b.snapshot()).filter(l -> !l.isEmpty()).orElseGet(() -> eventStore.findAll(id));
    }

    private void validate(ExperimentConfiguration cfg) {
        Set<ConstraintViolation<ExperimentConfiguration>> violations = validator.validate(cfg);
        if (!violations.isEmpty()) {
            List<String> details = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList();
            throw ApiException.validation("Invalid configuration: " + details.getFirst(), details);
        }
        if (cfg.transactions() < 1) {
            throw ApiException.validation("transactions must be >= 1");
        }
    }

    public static String newId(String prefix) {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        return prefix + "-" + HexFormat.of().withUpperCase().formatHex(b);
    }
}
