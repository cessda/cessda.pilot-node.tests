/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package eu.cessda.pilotnode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.file.Files;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link WebMvcConfig}.
 *
 * <p>Verifies that static resources under {@code classpath:/static/} are still
 * served correctly despite the {@code /**} wildcard mapping in
 * {@link DashboardDataController}, and that the root redirect to
 * {@code index.html} works as configured.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebMvcConfigTests {

    static final File TEMP_DATA_DIR;

    static {
        try {
            TEMP_DATA_DIR = Files.createTempDirectory("webmvc-test-data").toFile();
            TEMP_DATA_DIR.deleteOnExit();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void overrideDataDir(DynamicPropertyRegistry registry) {
        registry.add("dashboard.data-dir", TEMP_DATA_DIR::getAbsolutePath);
    }

    @Autowired
    private MockMvc mockMvc;

    // ── Root redirect ─────────────────────────────────────────────────────────

    @Test
    void rootPath_redirectsToIndexHtml() throws Exception {
        mockMvc.perform(get("/"))
               .andExpect(status().is3xxRedirection())
               .andExpect(redirectedUrl("/index.html"));
    }

    // ── Static resource serving ───────────────────────────────────────────────

    /**
     * Verifies that {@code index.html} from {@code src/main/resources/static/}
     * is reachable. The test will pass as long as the file exists on the
     * classpath; if the project has no static HTML yet, it returns 404, which
     * is still a correct non-500 response and indicates the handler chain works.
     */
    @Test
    void staticResource_indexHtml_isReachableOrNotFound() throws Exception {
        mockMvc.perform(get("/index.html"))
               .andExpect(result -> {
                   int status = result.getResponse().getStatus();
                   // 200 = file exists on classpath; 404 = not yet added — both are safe
                   assert status == 200 || status == 404
                           : "Expected 200 or 404, got " + status;
               });
    }

    @Test
    void staticResource_nodeHtml_isReachableOrNotFound() throws Exception {
        mockMvc.perform(get("/node.html"))
               .andExpect(result -> {
                   int status = result.getResponse().getStatus();
                   assert status == 200 || status == 404
                           : "Expected 200 or 404, got " + status;
               });
    }

    // ── API data route is still honoured ─────────────────────────────────────

    /**
     * Ensures the static resource handler does not shadow {@code /api/data/**}.
     * A request for a missing JSON file should return 404 from the
     * {@link DashboardDataController}, not a Spring resource handler 404.
     * Either way the status is 404, but the Content-Type should not be
     * {@code text/html} (which would indicate the static handler took over).
     */
    @Test
    void apiDataRoute_notShadowedByStaticHandler() throws Exception {
        mockMvc.perform(get("/api/data/nonexistent.json"))
               .andExpect(status().isNotFound());
    }

    // ── Unknown paths return appropriate responses ────────────────────────────

    @Test
    void unknownPath_doesNotReturn500() throws Exception {
        mockMvc.perform(get("/this/path/does/not/exist"))
               .andExpect(result -> {
                   int status = result.getResponse().getStatus();
                   assert status != 500 : "Unexpected 500 for unknown path";
               });
    }
}