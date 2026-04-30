/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package eu.cessda.pilotnode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@link DashboardDataController}.
 *
 * <p>Uses {@link SpringBootTest} with {@link MockMvc} so the full Spring MVC
 * pipeline (path traversal guard, content-type negotiation, etc.) is exercised
 * against a real temp directory.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardDataControllerTests {

    // A single shared temp directory wired into the Spring context via a
    // dynamic property. Declared static so it is available before the context
    // starts (required by @DynamicPropertySource).
    static final File TEMP_DATA_DIR;

    static {
        try {
            TEMP_DATA_DIR = Files.createTempDirectory("dashboard-test-data").toFile();
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

    /** Valid JSON placed in the temp directory before each test. */
    private static final String SAMPLE_JSON = "{\"key\":\"value\"}";

    @BeforeEach
    void setUp() throws Exception {
        // Ensure a clean, known file is present for positive tests
        Path file = TEMP_DATA_DIR.toPath().resolve("test.json");
        Files.writeString(file, SAMPLE_JSON);

        // Create a subdirectory file for path-traversal tests
        Path sub = TEMP_DATA_DIR.toPath().resolve("sub");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("data.json"), SAMPLE_JSON);
    }

    // ── Happy-path: serve a JSON file ─────────────────────────────────────────

    @Test
    void getJson_existingFile_returnsOkWithJsonContentType() throws Exception {
        mockMvc.perform(get("/api/data/test.json"))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
               .andExpect(content().json(SAMPLE_JSON));
    }

    @Test
    void getJson_fileInSubdirectory_returnsOk() throws Exception {
        mockMvc.perform(get("/api/data/sub/data.json"))
               .andExpect(status().isOk())
               .andExpect(content().json(SAMPLE_JSON));
    }

    // ── 404 cases ─────────────────────────────────────────────────────────────

    @Test
    void getJson_nonExistentFile_returns404() throws Exception {
        mockMvc.perform(get("/api/data/does-not-exist.json"))
               .andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "Non-JSON extension: {0}")
    @ValueSource(strings = {
        "/api/data/test.txt",
        "/api/data/test.xml",
        "/api/data/test.html",
        "/api/data/test"          // no extension
    })
    void getNonJsonFile_returns404(String path) throws Exception {
        mockMvc.perform(get(path))
               .andExpect(status().isNotFound());
    }

    // ── Path-traversal guard ──────────────────────────────────────────────────

    @Test
    void pathTraversal_attemptWithDotDot_isBadRequestOrNotFound() throws Exception {
        // The controller returns 400 on traversal detection, 404 if the
        // normalised path happens to not exist (server-dependent). Both are safe.
        mockMvc.perform(get("/api/data/../../etc/passwd"))
               .andExpect(result ->
                   assertThat(result.getResponse().getStatus())
                       .isIn(400, 404));
    }

    // ── Root path ─────────────────────────────────────────────────────────────

    @Test
    void rootPath_withoutJsonExtension_returns404() throws Exception {
        // /api/data/ alone does not end with .json
        mockMvc.perform(get("/api/data/"))
               .andExpect(status().isNotFound());
    }
}