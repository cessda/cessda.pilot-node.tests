package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Metric 9 queries the Discovery Hub by the name it knows a Node by, which can differ from the
 * registry name; a Node with no (or a blank) alias is queried by its own name.
 */
class DiscoveryHubNodeAliasTest {

    @Test
    void blankAndMissingEntriesMeanNoAlias() {
        Map<String, String> aliases = CheckOtherMetrics.parseDiscoveryHubNodeAliases(
                "EOSC-Beyond=, CESSDA=,LifeWatch-ERIC=LifeWatch ,EGI Pilot Node=,ENES");

        assertEquals(Map.of("LifeWatch-ERIC", "LifeWatch"), aliases);
    }

    @Test
    void aliasIsUsedWhenPresentOtherwiseTheNodeName() {
        Map<String, String> aliases = CheckOtherMetrics.parseDiscoveryHubNodeAliases("LifeWatch-ERIC=LifeWatch");

        assertEquals("LifeWatch", CheckOtherMetrics.discoveryHubNodeName(aliases, "LifeWatch-ERIC"));
        assertEquals("CESSDA", CheckOtherMetrics.discoveryHubNodeName(aliases, "CESSDA"));
        assertEquals("CESSDA", CheckOtherMetrics.discoveryHubNodeName(Map.of(), "CESSDA"));
        assertEquals("CESSDA", CheckOtherMetrics.discoveryHubNodeName(null, "CESSDA"));
        assertEquals("CESSDA", CheckOtherMetrics.discoveryHubNodeName(Map.of("CESSDA", "  "), "CESSDA"));
    }

    @Test
    void nullOrEmptyPropertyGivesNoAliases() {
        assertTrue(CheckOtherMetrics.parseDiscoveryHubNodeAliases(null).isEmpty());
        assertTrue(CheckOtherMetrics.parseDiscoveryHubNodeAliases("").isEmpty());
    }

    @Test
    void queryUrlFiltersOnTheAliasNotTheRegistryName() {
        String url = CheckOtherMetrics.buildResearchOutputQueryUrl("dataset",
                CheckOtherMetrics.discoveryHubNodeName(Map.of("LifeWatch-ERIC", "LifeWatch"), "LifeWatch-ERIC")).toString();

        assertTrue(url.contains("fq=node:(%22LifeWatch%22)"), url);
    }
}
