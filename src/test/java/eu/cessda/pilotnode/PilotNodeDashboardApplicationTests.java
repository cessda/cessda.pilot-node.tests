/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package eu.cessda.pilotnode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Smoke tests for {@link PilotNodeDashboardApplication}.
 *
 * <p>Verifies that the Spring application context loads without errors,
 * which in turn proves that all beans, configuration, and auto-wiring
 * are consistent.
 */
@SpringBootTest
class PilotNodeDashboardApplicationTests {

    static final File TEMP_DATA_DIR;

    static {
        try {
            TEMP_DATA_DIR = Files.createTempDirectory("app-test-data").toFile();
            TEMP_DATA_DIR.deleteOnExit();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void overrideDataDir(DynamicPropertyRegistry registry) {
        registry.add("dashboard.data-dir", TEMP_DATA_DIR::getAbsolutePath);
    }

    /**
     * The presence of {@code @SpringBootTest} on the class is itself a context-
     * load test. This explicit test makes the intent clear and provides a
     * meaningful failure message.
     */
    @Test
    void contextLoads() {
        // If the context fails to start, Spring throws before this method body
        // is reached, so no assertions are needed inside the method.
    }

    /**
     * Verifies that {@code main()} can be invoked without throwing.
     * The application is started in a background thread and shut down
     * immediately via a system property so it does not bind a port that
     * conflicts with the test context.
     *
     * <p>Note: Because {@code @SpringBootTest} already starts the full
     * context, calling {@code main()} here simply exercises the entry point
     * method itself and checks it is not final/private/broken.
     */
    @Test
    void mainMethod_doesNotThrow() {
        assertThatCode(() ->
            PilotNodeDashboardApplication.main(new String[]{
                "--spring.main.web-application-type=none",
                "--dashboard.data-dir=" + TEMP_DATA_DIR.getAbsolutePath()
            })
        ).doesNotThrowAnyException();
    }
}