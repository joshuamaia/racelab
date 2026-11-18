package dev.racelab.web;

import dev.racelab.application.catalog.PresetCatalog;
import dev.racelab.application.engine.ExperimentRegistry;
import dev.racelab.application.service.CodeExampleService;
import dev.racelab.domain.model.ExperimentPreset;
import dev.racelab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ApiIT extends IntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    PresetCatalog presets;
    @Autowired
    ExperimentRegistry registry;
    @Autowired
    CodeExampleService code;

    @Test
    void rejectsConfigurationsBeyondTheLimits() throws Exception {
        mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "corr-123")
                        .content("{\"scenarioId\":\"bank-withdrawal\",\"configuration\":{\"threads\":500}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.correlationId").value("corr-123"))
                .andExpect(header().string("X-Correlation-Id", "corr-123"));
    }

    @Test
    void rejectsUnsupportedStrategyAndUnknownScenario() throws Exception {
        mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"deadlock\",\"strategy\":\"OPTIMISTIC_LOCKING\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_STRATEGY"));
        mvc.perform(get("/api/scenarios/nope")).andExpect(status().isNotFound());
    }

    @Test
    void everyPresetReferencesAnExistingScenarioAndSupportedStrategies() {
        assertThat(presets.all()).hasSizeGreaterThanOrEqualTo(33);
        for (ExperimentPreset p : presets.all()) {
            var scenario = registry.get(p.scenarioId()).descriptor();
            assertThat(scenario.supportedStrategies()).as(p.id()).contains(p.strategy());
            assertThat(scenario.supportedStrategies()).as(p.id()).containsAll(p.availableStrategies());
        }
        assertThat(presets.all().stream().filter(p -> p.demo() != null)).hasSize(8);
    }

    @Test
    void codeExamplesComeFromTheRealSources() {
        var ex = code.example("bank-withdrawal", dev.racelab.domain.model.StrategyType.ATOMIC_UPDATE);
        assertThat(ex.sql()).anyMatch(b -> b.code().contains("balance >= ?"));
        assertThat(ex.java()).anyMatch(b -> b.source().equals("BankWithdrawalExperiment.java"));
        assertThat(ex.baseline().java()).isNotEmpty();
        for (var s : registry.descriptors()) {
            for (var strategy : s.supportedStrategies()) {
                assertThat(code.example(s.id(), strategy).java()).as(s.id() + "/" + strategy).isNotEmpty();
            }
        }
    }

    @Test
    void catalogDashboardInspectorAndSystemEndpointsRespond() throws Exception {
        mvc.perform(get("/api/catalog")).andExpect(status().isOk());
        mvc.perform(get("/api/strategies")).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk()).andExpect(jsonPath("$.totals.experiments").exists());
        mvc.perform(get("/api/inspector/snapshot")).andExpect(status().isOk()).andExpect(jsonPath("$.note").exists());
        mvc.perform(get("/api/system/info")).andExpect(status().isOk()).andExpect(jsonPath("$.instanceId").value("primary"));
        mvc.perform(get("/api/docs")).andExpect(status().isOk());
    }
}
