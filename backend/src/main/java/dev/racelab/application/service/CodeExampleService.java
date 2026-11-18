package dev.racelab.application.service;

import dev.racelab.application.engine.ExperimentRegistry;
import dev.racelab.application.engine.StrategyRegistry;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.StrategyDescriptor;
import dev.racelab.domain.model.StrategyType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Serves the code that really runs. The build copies the scenario sources to {@code code-snippets/}; regions
 * delimited by {@code // @snippet:start <scenario> <strategies|*> <java|sql> "title"} and
 * {@code // @snippet:end} are extracted at startup. Nothing here is hand-written sample code.
 */
@Service
public class CodeExampleService {

    private static final Logger log = LoggerFactory.getLogger(CodeExampleService.class);
    private static final Pattern START = Pattern.compile(
            "//\\s*@snippet:start\\s+(\\S+)\\s+(\\S+)\\s+(java|sql)\\s+\"([^\"]*)\"");
    private static final Pattern END = Pattern.compile("//\\s*@snippet:end");

    private final ExperimentRegistry registry;
    private final StrategyRegistry strategies;
    private final List<Snippet> snippets = new ArrayList<>();

    public CodeExampleService(ExperimentRegistry registry, StrategyRegistry strategies) {
        this.registry = registry;
        this.strategies = strategies;
    }

    public record Snippet(String scenarioId, Set<String> strategies, String language, String title, String source,
                          String code) {
        boolean matches(String scenario, StrategyType strategy, boolean includeCommon) {
            if (!Set.of(scenarioId.split(",")).contains(scenario)) {
                return false;
            }
            return strategies.contains(strategy.name()) || (includeCommon && strategies.contains("*"));
        }
    }

    public record CodeBlock(String title, String source, String language, String code) {
    }

    public record CodeSet(StrategyType strategy, List<CodeBlock> java, List<CodeBlock> sql) {
    }

    public record CodeExample(String scenarioId, StrategyType strategy, List<CodeBlock> java, List<CodeBlock> sql,
                              CodeSet baseline, CodeBlock typescript, ExperimentConfiguration configuration,
                              String explanation, List<String> advantages, List<String> limitations) {
    }

    @PostConstruct
    void load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:code-snippets/**/*.java");
            for (Resource r : resources) {
                parse(r.getFilename(), r.getContentAsString(StandardCharsets.UTF_8));
            }
            log.info("Loaded {} code snippets from {} source files", snippets.size(), resources.length);
        } catch (IOException e) {
            log.warn("Could not load code snippets: {}", e.getMessage());
        }
    }

    void parse(String fileName, String content) {
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = START.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            List<String> body = new ArrayList<>();
            int j = i + 1;
            while (j < lines.length && !END.matcher(lines[j]).find()) {
                if (!START.matcher(lines[j]).find()) {
                    body.add(lines[j]);
                }
                j++;
            }
            String language = m.group(3);
            String code = "sql".equals(language) ? extractSql(body) : dedent(body);
            Set<String> strategySet = Arrays.stream(m.group(2).split(",")).map(String::trim).collect(Collectors.toSet());
            snippets.add(new Snippet(m.group(1), strategySet, language, m.group(4), fileName, code));
            i = j;
        }
    }

    public List<Snippet> all() {
        return List.copyOf(snippets);
    }

    public CodeExample example(String scenarioId, StrategyType strategy) {
        ScenarioDescriptor scenario = registry.get(scenarioId).descriptor();
        StrategyDescriptor sd = strategies.get(strategy);
        StrategyType baselineStrategy = scenario.defaultStrategy() != strategy ? scenario.defaultStrategy() : null;
        CodeSet baseline = baselineStrategy == null ? null
                : new CodeSet(baselineStrategy, blocks(scenarioId, baselineStrategy, "java", false),
                blocks(scenarioId, baselineStrategy, "sql", false));
        return new CodeExample(scenarioId, strategy, blocks(scenarioId, strategy, "java", true),
                blocks(scenarioId, strategy, "sql", true), baseline, typescript(scenarioId, strategy), scenario.defaults(),
                scenario.description() + " " + (sd == null ? "" : sd.description()),
                sd == null ? List.of() : sd.advantages(), sd == null ? List.of() : sd.limitations());
    }

    private List<CodeBlock> blocks(String scenarioId, StrategyType strategy, String language, boolean includeCommon) {
        List<CodeBlock> specific = new ArrayList<>();
        List<CodeBlock> common = new ArrayList<>();
        for (Snippet s : snippets) {
            if (!s.language().equals(language) || !s.matches(scenarioId, strategy, includeCommon)) {
                continue;
            }
            CodeBlock b = new CodeBlock(s.title(), s.source(), language, s.code());
            if (s.strategies().contains(strategy.name())) {
                specific.add(b);
            } else {
                common.add(b);
            }
        }
        specific.addAll(common);
        return specific;
    }

    private static CodeBlock typescript(String scenarioId, StrategyType strategy) {
        String code = """
                // Generated API usage example: run this experiment through the RaceLab BFF.
                const api = '/api';

                const created = await fetch(`${api}/experiments`, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ scenarioId: '%s', strategy: '%s', autoStart: false }),
                }).then((r) => r.json());

                const events = new EventSource(`${api}/experiments/${created.id}/events`);
                events.addEventListener('experiment-event', (e) => {
                  const event = JSON.parse((e as MessageEvent).data);
                  console.log(event.sequence, event.workerId ?? '-', event.eventType, event.message);
                });
                events.addEventListener('end', async () => {
                  events.close();
                  const result = await fetch(`${api}/experiments/${created.id}/result`).then((r) => r.json());
                  console.log(result.consistent ? 'consistent' : 'INCONSISTENT', result.summary);
                });

                await fetch(`${api}/experiments/${created.id}/start`, { method: 'POST' });
                """.formatted(scenarioId, strategy.name());
        return new CodeBlock("Start this experiment from TypeScript", "generated", "typescript", code);
    }

    private static String extractSql(List<String> body) {
        String joined = String.join("\n", body);
        List<String> parts = new ArrayList<>();
        Matcher tb = Pattern.compile("\"\"\"\\s*\\n(.*?)\"\"\"", Pattern.DOTALL).matcher(joined);
        while (tb.find()) {
            parts.add(dedent(Arrays.asList(tb.group(1).split("\\R"))).strip());
        }
        Matcher single = Pattern.compile("=\\s*\"([^\"]+)\"\\s*;").matcher(joined);
        while (single.find()) {
            parts.add(single.group(1));
        }
        return parts.isEmpty() ? dedent(body) : String.join(";\n\n", parts) + ";";
    }

    static String dedent(List<String> lines) {
        int indent = lines.stream().filter(l -> !l.isBlank())
                .mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        return lines.stream().map(l -> l.isBlank() ? "" : l.substring(Math.min(indent, l.length())))
                .collect(Collectors.joining("\n")).strip();
    }
}
