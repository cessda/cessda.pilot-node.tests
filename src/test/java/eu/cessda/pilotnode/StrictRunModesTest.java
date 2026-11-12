package eu.cessda.pilotnode;

import static eu.cessda.pilotnode.catalogue.FakeNode.MAPPER;
import static eu.cessda.pilotnode.catalogue.FakeNode.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import eu.cessda.pilotnode.catalogue.lenient.CatalogueSelector;
import eu.cessda.pilotnode.catalogue.FakeNode;
import eu.cessda.pilotnode.common.CheckMode;

/** Choosing the mode when a check is started: the REST requests and Check All. */
@SpringBootTest
@AutoConfigureMockMvc
class StrictRunModesTest {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path dataDir;

    // ── REST requests ────────────────────────────────────────────────────────

    @Test
    void anUnknownModeIsABadRequestForNodeCapabilities() throws Exception {
        mockMvc.perform(post("/api/run/node-capabilities").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"harsh\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("harsh")));
    }

    @Test
    void anUnknownModeIsABadRequestForCheckAll() throws Exception {
        mockMvc.perform(post("/api/run/check-all").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"harsh\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownModeIsABadRequestForExchangeServices() throws Exception {
        mockMvc.perform(post("/api/run/catalogue-services").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"node\":\"N\",\"mode\":\"harsh\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStrictExchangeServicesRunTakesNoCatalogueUrl() throws Exception {
        mockMvc.perform(post("/api/run/catalogue-services").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"node\":\"N\",\"mode\":\"strict\",\"catalogueUrl\":\"https://example.org/api\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("catalogueUrl")));
    }

    // ── Check All's Exchange Services step ───────────────────────────────────

    @Test
    void aStrictCheckAllStepReadsAndWritesTheStrictDirectory() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        CatalogueSelector selector = CatalogueSelector.standard(List.of("Service Catalogue", "Resource Catalogue"), http, MAPPER);
        try (FakeNode node = new FakeNode()) {
            List<ObjectNode> services = FakeNode.lot1Services(2, FakeNode.V2_CATEGORY);
            services.forEach(s -> s.put("webpage", node.base() + "/page"));
            node.route("/page", q -> new FakeNode.Reply(200, "{}"));
            node.lot1("/svc/services", services);
            Path strictNode = dataDir.resolve("N");
            Files.createDirectories(strictNode);
            MAPPER.writeValue(strictNode.resolve("endpoint_report.json").toFile(), FakeNode.strictReport("21.T15999/N",
                    row("Service Catalogue", node.base() + "/svc/services", "OPERATIONAL")));

            CheckAll.runCatalogueServices(dataDir, "N", selector, MAPPER, http, CheckMode.STRICT);

            JsonNode report = MAPPER.readTree(strictNode.resolve("catalogue_services_report.json").toFile());
            assertEquals("strict", report.path("mode").asText());
            assertFalse(Files.exists(dataDir.resolve("N").resolve("lenient").resolve("catalogue_services_report.json")),
                    "a strict run does not touch the lenient report");
        }
    }

    @Test
    void aStrictCheckAllStepDoesNotUseTheLenientEndpointReport() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        CatalogueSelector selector = CatalogueSelector.standard(List.of("Service Catalogue", "Resource Catalogue"), http, MAPPER);
        Path lenientNode = dataDir.resolve("N").resolve("lenient");
        Files.createDirectories(lenientNode);
        MAPPER.writeValue(lenientNode.resolve("endpoint_report.json").toFile(),
                FakeNode.report("21.T15999/N", row("Service Catalogue", "http://127.0.0.1:1/x", "OPERATIONAL")));

        var e = assertThrows(Exception.class,
                () -> CheckAll.runCatalogueServices(dataDir, "N", selector, MAPPER, http, CheckMode.STRICT));
        assertTrue(e.getMessage().contains("no strict endpoint_report.json"), e.getMessage());
    }

    // ── the summary ──────────────────────────────────────────────────────────

    @Test
    void aStrictSummarySaysWhichChecksWereNotRun() {
        CheckAll.Result result = new CheckAll.Result(3);
        result.mode = CheckMode.STRICT;
        String summary = result.summary();
        assertTrue(summary.startsWith("Strict mode."), summary);
        assertTrue(summary.contains("have no strict mode and were not run"), summary);
        assertFalse(summary.contains("Service Monitoring:"), summary);
    }

    @Test
    void aLenientSummaryIsAsBefore() {
        String summary = new CheckAll.Result(3).summary();
        assertFalse(summary.contains("Strict"), summary);
        assertTrue(summary.contains("Federated Search: 0 ok, 0 failed."), summary);
    }
}
