package eu.cessda.pilotnode;

import static eu.cessda.pilotnode.catalogue.FakeNode.report;
import static eu.cessda.pilotnode.catalogue.FakeNode.row;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import eu.cessda.pilotnode.catalogue.CatalogueSelector;

/** The capability order comes from {@code check.catalogue.capability-order}. */
class CatalogueConfigTest {

    private static List<String> order(CheckRunnerController controller) {
        var selector = (CatalogueSelector) ReflectionTestUtils.getField(controller, "catalogueSelector");
        var report = report("21.T15999/N",
                row("Resource Catalogue", "https://a/rc", "OPERATIONAL"),
                row("Service Catalogue", "https://a/sc", "OPERATIONAL"),
                row("AAI", "https://a/aai", "OPERATIONAL"));
        return selector.candidates(report).stream().map(c -> c.capabilityType()).toList();
    }

    @Nested
    @SpringBootTest
    class Default {
        @Autowired
        CheckRunnerController controller;

        @Test
        void serviceCatalogueComesBeforeResourceCatalogue() {
            assertEquals(List.of("Service Catalogue", "Resource Catalogue"), order(controller));
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = "check.catalogue.capability-order=Resource Catalogue")
    class Overridden {
        @Autowired
        CheckRunnerController controller;

        @Test
        void aShorterListLeavesTheOtherTypeOut() {
            assertEquals(List.of("Resource Catalogue"), order(controller));
        }
    }
}
