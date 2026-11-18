package dev.racelab.infrastructure.web;

import dev.racelab.application.service.ExperimentService;
import dev.racelab.application.service.ExperimentView;
import dev.racelab.application.service.ExportService;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.Metrics;
import dev.racelab.domain.model.Recommendation;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.Violation;
import dev.racelab.domain.port.ExperimentRepository;
import dev.racelab.infrastructure.web.sse.EventStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Tag(name = "Experiments", description = "Create, run, observe and export real concurrency experiments")
@RestController
@Validated
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experiments;
    private final EventStreamService streams;
    private final ExportService exports;

    public ExperimentController(ExperimentService experiments, EventStreamService streams, ExportService exports) {
        this.experiments = experiments;
        this.streams = streams;
        this.exports = exports;
    }

    public record CreateExperimentRequest(
            @Pattern(regexp = "^[a-z0-9-]{1,64}$") String presetId,
            @Pattern(regexp = "^[a-z0-9-]{1,64}$") String scenarioId,
            StrategyType strategy,
            @Size(max = 200) String name,
            ExperimentConfiguration configuration,
            Boolean autoStart) {
    }

    public record StepRequest(@Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") String workerId) {
    }

    public record RerunRequest(Boolean autoStart) {
    }

    @Operation(summary = "Create an experiment (optionally start it). Returns the experiment id used by the SSE stream.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExperimentView create(@RequestBody @jakarta.validation.Valid CreateExperimentRequest request) {
        return experiments.create(new ExperimentService.CreateCommand(request.presetId(), request.scenarioId(),
                request.strategy(), request.name(), request.configuration(), Boolean.TRUE.equals(request.autoStart()),
                MDC.get("correlationId"), null, false, null));
    }

    @Operation(summary = "List experiments (history), newest first")
    @GetMapping
    public ExperimentRepository.PageResult<ExperimentView> list(@RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size,
                                                                @RequestParam(required = false) String category,
                                                                @RequestParam(required = false) String status,
                                                                @RequestParam(required = false) String scenarioId,
                                                                @RequestParam(required = false) String strategy,
                                                                @RequestParam(defaultValue = "false") boolean includeComparisonRuns) {
        return experiments.search(new ExperimentRepository.SearchFilter(category, status, scenarioId, strategy,
                includeComparisonRuns), page, size);
    }

    @GetMapping("/{id}")
    public ExperimentView get(@PathVariable String id) {
        return experiments.view(id);
    }

    @PostMapping("/{id}/start")
    public ExperimentView start(@PathVariable String id) {
        return experiments.start(id);
    }

    @Operation(summary = "Step-by-step mode: release the next checkpoint (or the given worker)")
    @PostMapping("/{id}/step")
    public ExperimentView step(@PathVariable String id, @RequestBody(required = false) StepRequest request) {
        return experiments.step(id, request == null ? null : request.workerId());
    }

    @Operation(summary = "Leave step-by-step mode and let the experiment run freely")
    @PostMapping("/{id}/resume")
    public ExperimentView resume(@PathVariable String id) {
        return experiments.resume(id);
    }

    @Operation(summary = "Cancel cooperatively; blocked statements are cancelled with pg_cancel_backend")
    @PostMapping("/{id}/cancel")
    public ExperimentView cancel(@PathVariable String id) {
        return experiments.cancel(id);
    }

    @Operation(summary = "Run again with the same configuration (new id, new isolated data)")
    @PostMapping("/{id}/rerun")
    @ResponseStatus(HttpStatus.CREATED)
    public ExperimentView rerun(@PathVariable String id, @RequestBody(required = false) RerunRequest request) {
        return experiments.rerun(id, request != null && Boolean.TRUE.equals(request.autoStart()), MDC.get("correlationId"));
    }

    @Operation(summary = "Real-time events (Server-Sent Events). Supports Last-Event-ID replay.")
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
                             @RequestParam(value = "lastEventId", required = false) Long lastEventIdParam) {
        long last = lastEventIdParam != null ? lastEventIdParam : parse(lastEventIdHeader);
        return streams.open(id, last);
    }

    @Operation(summary = "Persisted events as JSON")
    @GetMapping(path = "/{id}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ExperimentEvent> events(@PathVariable String id) {
        return experiments.events(id);
    }

    @GetMapping("/{id}/result")
    public dev.racelab.domain.model.ExperimentResult result(@PathVariable String id) {
        return experiments.result(id);
    }

    @GetMapping("/{id}/metrics")
    public Metrics metrics(@PathVariable String id) {
        return experiments.result(id).metrics();
    }

    @GetMapping("/{id}/transactions")
    public List<TransactionOutcome> transactions(@PathVariable String id) {
        return experiments.result(id).transactions();
    }

    @GetMapping("/{id}/violations")
    public List<Violation> violations(@PathVariable String id) {
        return experiments.result(id).violations();
    }

    @GetMapping("/{id}/recommendations")
    public List<Recommendation> recommendations(@PathVariable String id) {
        return experiments.result(id).recommendations();
    }

    @Operation(summary = "Export the report as json, csv, markdown or pdf")
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(defaultValue = "json") String format) {
        ExportService.Export export = exports.export(id, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName() + "\"")
                .contentType(MediaType.parseMediaType(export.contentType()))
                .body(export.content());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        experiments.delete(id);
    }

    private static long parse(String header) {
        if (header == null || header.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
