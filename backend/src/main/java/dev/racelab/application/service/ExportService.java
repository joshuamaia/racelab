package dev.racelab.application.service;

import dev.racelab.application.exception.ApiException;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.ExperimentRecord;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.Recommendation;
import dev.racelab.domain.model.StateRow;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.Violation;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Experiment reports in JSON, CSV, Markdown and PDF. */
@Service
public class ExportService {

    private final ExperimentService experiments;
    private final CodeExampleService code;
    private final EnvironmentInfo environment;
    private final ObjectMapper json;

    public ExportService(ExperimentService experiments, CodeExampleService code, EnvironmentInfo environment,
                         ObjectMapper json) {
        this.experiments = experiments;
        this.code = code;
        this.environment = environment;
        this.json = json;
    }

    public record Export(String fileName, String contentType, byte[] content) {
    }

    public Export export(String id, String format) {
        ExperimentRecord record = experiments.record(id);
        ExperimentResult result = experiments.result(id);
        List<ExperimentEvent> events = experiments.events(id);
        return switch (format.toLowerCase()) {
            case "json" -> new Export(id + ".json", "application/json", json(record, result, events));
            case "csv" -> new Export(id + ".csv", "text/csv", csv(record, result, events).getBytes(StandardCharsets.UTF_8));
            case "md", "markdown" -> new Export(id + ".md", "text/markdown",
                    markdown(record, result, events).getBytes(StandardCharsets.UTF_8));
            case "pdf" -> new Export(id + ".pdf", "application/pdf", pdf(record, result, events));
            default -> throw ApiException.validation("Unsupported format '" + format + "' (json, csv, markdown, pdf)");
        };
    }

    private byte[] json(ExperimentRecord record, ExperimentResult result, List<ExperimentEvent> events) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("experiment", record);
        report.put("result", result);
        report.put("timeline", events);
        report.put("codeExample", code.example(record.scenarioId(), record.strategy()));
        report.put("environment", environment.snapshot());
        return json.writer().with(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(report);
    }

    private String csv(ExperimentRecord record, ExperimentResult result, List<ExperimentEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("# experiment\nid,name,scenario,strategy,status,consistent,durationMs\n");
        sb.append(String.join(",", q(record.id()), q(record.name()), q(record.scenarioId()), q(record.strategy().name()),
                q(record.status().name()), q(String.valueOf(result.consistent())), q(String.valueOf(record.durationMs())))).append('\n');
        sb.append("\n# metrics\nmetric,value\n");
        result.metrics().asMap().forEach((k, v) -> sb.append(q(k)).append(',').append(v).append('\n'));
        sb.append("\n# state\nresource,attribute,initial,actual,expected,ok\n");
        for (StateRow r : result.stateRows()) {
            sb.append(String.join(",", q(r.resource()), q(r.attribute()), q(r.initial()), q(r.actual()), q(r.expected()),
                    String.valueOf(r.ok()))).append('\n');
        }
        sb.append("\n# transactions\nworker,transaction,status,attempts,latencyMs,lockWaitMs,sqlState,errorType,message\n");
        for (TransactionOutcome t : result.transactions()) {
            sb.append(String.join(",", q(t.workerId()), q(t.transactionId()), q(t.status().name()),
                    String.valueOf(t.attempts()), String.valueOf(t.latencyMs()), String.valueOf(t.lockWaitMs()),
                    q(t.sqlState()), q(t.errorType() == null ? null : t.errorType().name()), q(t.message()))).append('\n');
        }
        sb.append("\n# violations\nrule,expected,actual,difference,severity\n");
        for (Violation v : result.violations()) {
            sb.append(String.join(",", q(v.rule()), q(v.expected()), q(v.actual()), q(v.difference()),
                    q(v.severity().name()))).append('\n');
        }
        sb.append("\n# events\nsequence,elapsedMs,worker,transaction,type,source,resource,message\n");
        for (ExperimentEvent e : events) {
            sb.append(String.join(",", String.valueOf(e.sequence()), String.valueOf(e.elapsedTimeMs()), q(e.workerId()),
                    q(e.transactionId()), q(e.eventType().name()), q(e.source().name()), q(e.resource()), q(e.message())))
                    .append('\n');
        }
        return sb.toString();
    }

    private String markdown(ExperimentRecord record, ExperimentResult result, List<ExperimentEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RaceLab report — ").append(record.name()).append("\n\n");
        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| Experiment | `").append(record.id()).append("` |\n");
        sb.append("| Scenario | ").append(record.scenarioId()).append(" |\n");
        sb.append("| Strategy | ").append(record.strategy()).append(" |\n");
        sb.append("| Status | ").append(record.status()).append(" |\n");
        sb.append("| Consistency | ").append(result.consistent() ? "VALID" : "**INVALID**").append(" |\n");
        sb.append("| Executed at | ").append(record.startedAt()).append(" |\n");
        sb.append("| Duration | ").append(record.durationMs()).append(" ms |\n\n");
        sb.append("> ").append(result.summary()).append("\n\n");
        sb.append("## Configuration\n\n```json\n")
                .append(json.writer().with(SerializationFeature.INDENT_OUTPUT).writeValueAsString(record.configuration()))
                .append("\n```\n\n");
        sb.append("## State\n\n| Resource | Attribute | Initial | Current | Expected | Status |\n|---|---|---|---|---|---|\n");
        for (StateRow r : result.stateRows()) {
            sb.append("| ").append(r.label()).append(" | ").append(r.attribute()).append(" | ").append(n(r.initial()))
                    .append(" | ").append(n(r.actual())).append(" | ").append(n(r.expected())).append(" | ")
                    .append(r.ok() ? "OK" : "**INVALID**").append(" |\n");
        }
        sb.append("\n## Metrics\n\n| Metric | Value |\n|---|---|\n");
        result.metrics().asMap().forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        sb.append("\n## Transactions\n\n| Worker | Transaction | Status | Attempts | Latency (ms) | Lock wait (ms) | SQLSTATE | Detail |\n|---|---|---|---|---|---|---|---|\n");
        for (TransactionOutcome t : result.transactions()) {
            sb.append("| ").append(t.workerId()).append(" | ").append(n(t.transactionId())).append(" | ").append(t.status())
                    .append(" | ").append(t.attempts()).append(" | ").append(t.latencyMs()).append(" | ").append(t.lockWaitMs())
                    .append(" | ").append(n(t.sqlState())).append(" | ").append(n(t.friendlyMessage())).append(" |\n");
        }
        sb.append("\n## Inconsistencies\n\n");
        if (result.violations().isEmpty()) {
            sb.append("None — every business invariant holds.\n");
        }
        for (Violation v : result.violations()) {
            sb.append("- **").append(v.rule()).append("** (").append(v.severity()).append("): expected `")
                    .append(v.expected()).append("`, found `").append(v.actual()).append("`, difference `")
                    .append(v.difference()).append("`. ").append(v.possibleCause()).append("\n");
        }
        sb.append("\n## Recommendations\n\n");
        for (Recommendation r : result.recommendations()) {
            sb.append("### ").append(r.title()).append("\n\n").append(r.justification()).append("\n\n");
            r.benefits().forEach(b -> sb.append("- ✅ ").append(b).append('\n'));
            r.tradeOffs().forEach(b -> sb.append("- ⚠️ ").append(b).append('\n'));
            sb.append('\n');
        }
        sb.append("## Timeline (summary)\n\n| ms | Worker | Transaction | Event | Detail |\n|---|---|---|---|---|\n");
        events.stream().limit(400).forEach(e -> sb.append("| ").append(e.elapsedTimeMs()).append(" | ").append(n(e.workerId()))
                .append(" | ").append(n(e.transactionId())).append(" | ").append(e.eventType()).append(" | ")
                .append(n(e.message()).replace("|", "\\|")).append(" |\n"));
        var example = code.example(record.scenarioId(), record.strategy());
        sb.append("\n## Code\n\n");
        example.java().forEach(b -> sb.append("### ").append(b.title()).append("\n\n```java\n").append(b.code()).append("\n```\n\n"));
        example.sql().forEach(b -> sb.append("### ").append(b.title()).append("\n\n```sql\n").append(b.code()).append("\n```\n\n"));
        sb.append("## Environment\n\n");
        result.environment().forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        sb.append("\n_Results depend on hardware, database configuration, load and scenario._\n");
        return sb.toString();
    }

    private byte[] pdf(ExperimentRecord record, ExperimentResult result, List<ExperimentEvent> events) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font mono = FontFactory.getFont(FontFactory.COURIER, 7);
        doc.add(new Paragraph("RaceLab report — " + record.name(), h1));
        doc.add(new Paragraph(record.id() + " · " + record.scenarioId() + " · " + record.strategy() + " · " + record.status()
                + " · " + (result.consistent() ? "CONSISTENT" : "INCONSISTENT"), body));
        doc.add(new Paragraph(result.summary(), body));
        doc.add(Chunk.NEWLINE);
        doc.add(new Paragraph("State", h2));
        PdfPTable state = table(body, "Resource", "Attribute", "Initial", "Current", "Expected", "Status");
        for (StateRow r : result.stateRows()) {
            row(state, body, r.label(), r.attribute(), n(r.initial()), n(r.actual()), n(r.expected()), r.ok() ? "OK" : "INVALID");
        }
        doc.add(state);
        doc.add(new Paragraph("Metrics", h2));
        PdfPTable metrics = table(body, "Metric", "Value");
        result.metrics().asMap().forEach((k, v) -> row(metrics, body, k, String.valueOf(v)));
        doc.add(metrics);
        doc.add(new Paragraph("Transactions", h2));
        PdfPTable tx = table(body, "Worker", "Transaction", "Status", "Attempts", "Latency", "SQLSTATE");
        for (TransactionOutcome t : result.transactions()) {
            row(tx, body, t.workerId(), n(t.transactionId()), t.status().name(), String.valueOf(t.attempts()),
                    t.latencyMs() + " ms", n(t.sqlState()));
        }
        doc.add(tx);
        doc.add(new Paragraph("Inconsistencies", h2));
        if (result.violations().isEmpty()) {
            doc.add(new Paragraph("None — every business invariant holds.", body));
        }
        for (Violation v : result.violations()) {
            doc.add(new Paragraph("• " + v.rule() + " — expected " + v.expected() + ", found " + v.actual()
                    + " (" + v.severity() + "). " + v.possibleCause(), body));
        }
        doc.add(new Paragraph("Recommendations", h2));
        for (Recommendation r : result.recommendations()) {
            doc.add(new Paragraph("• " + r.title() + ": " + r.justification(), body));
        }
        doc.add(new Paragraph("Timeline (first 120 events)", h2));
        events.stream().limit(120).forEach(e -> doc.add(new Paragraph(String.format("%6d ms  %-9s %-12s %-26s %s",
                e.elapsedTimeMs(), n(e.workerId()), n(e.transactionId()), e.eventType(), n(e.message())), mono)));
        doc.add(new Paragraph("Code", h2));
        var example = code.example(record.scenarioId(), record.strategy());
        example.java().stream().limit(3).forEach(b -> {
            doc.add(new Paragraph(b.title(), body));
            doc.add(new Paragraph(b.code(), mono));
        });
        doc.add(new Paragraph("Environment", h2));
        result.environment().forEach((k, v) -> doc.add(new Paragraph(k + ": " + v, body)));
        doc.add(new Paragraph("Results depend on hardware, database configuration, load and scenario.", body));
        doc.close();
        return out.toByteArray();
    }

    private static PdfPTable table(Font font, String... headers) {
        PdfPTable t = new PdfPTable(headers.length);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        t.setSpacingAfter(8);
        for (String h : headers) {
            t.addCell(new Paragraph(h, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9)));
        }
        return t;
    }

    private static void row(PdfPTable t, Font font, String... cells) {
        for (String c : cells) {
            t.addCell(new Paragraph(c == null ? "" : c, font));
        }
    }

    private static String q(String v) {
        if (v == null) {
            return "";
        }
        return "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\"";
    }

    private static String n(String v) {
        return v == null ? "—" : v;
    }
}
