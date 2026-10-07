package eu.cessda.pilotnode.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/** Formats are recognised by what they contain, never by what the node says about them. */
class AdapterDetectionTest {

    private final BeyondAdapter beyond = new BeyondAdapter();
    private final Lot1Adapter lot1 = new Lot1Adapter(new Lot1Validator());

    private static JsonNode json(String s) throws IOException {
        return FakeNode.MAPPER.readTree(s);
    }

    @Test
    void aBeyondListIsRecognisedByBeyondOnly() {
        var list = FakeNode.beyondList("A", "B");
        assertTrue(beyond.detect(list).isPresent());
        assertFalse(lot1.detect(list).isPresent());
    }

    @Test
    void aLot1ListIsRecognisedByLot1Only() {
        var page = FakeNode.lot1Page(FakeNode.lot1Services(3, FakeNode.V2_CATEGORY), 0, 10);
        assertEquals("Lot-1 v2.0.0", lot1.detect(page).orElseThrow());
        assertFalse(beyond.detect(page).isPresent(), "{id, service} bundles are not a Beyond list");
    }

    @Test
    void theLot1ReleaseComesFromTheCategoryVocabulary() {
        assertEquals("Lot-1 v1.0.0",
                lot1.detect(FakeNode.lot1Page(FakeNode.lot1Services(2, FakeNode.V1_CATEGORY), 0, 10)).orElseThrow());
        assertEquals("Lot-1 v2.0.0",
                lot1.detect(FakeNode.lot1Page(FakeNode.lot1Services(2, FakeNode.V2_CATEGORY), 0, 10)).orElseThrow());
    }

    @Test
    void withoutCategoriesTheLatestReleaseIsAssumed() {
        var services = FakeNode.lot1Services(1, FakeNode.V1_CATEGORY);
        services.get(0).remove("categories");
        assertEquals("Lot-1 v2.0.0", lot1.detect(FakeNode.lot1Page(services, 0, 10)).orElseThrow());
    }

    @Test
    void aDcatFeedIsRecognisedByNobody() {
        var feed = FakeNode.dcatFeed();
        assertFalse(beyond.detect(feed).isPresent());
        assertFalse(lot1.detect(feed).isPresent());
    }

    @Test
    void anEmptyListIsClassifiedByItsEnvelope() throws IOException {
        assertTrue(beyond.detect(json("{\"total\":0,\"from\":0,\"to\":0,\"results\":[],\"facets\":[]}")).isPresent());
        assertFalse(lot1.detect(json("{\"total\":0,\"from\":0,\"to\":0,\"results\":[],\"facets\":[]}")).isPresent());
        assertTrue(lot1.detect(json("{\"total\":0,\"from\":0,\"to\":0,\"results\":[]}")).isPresent());
        assertFalse(beyond.detect(json("{\"total\":0,\"from\":0,\"to\":0,\"results\":[]}")).isPresent());
    }

    @Test
    void nonListJsonIsRecognisedByNobody() throws IOException {
        for (String body : List.of("[]", "\"text\"", "{}", "{\"error\":\"x\"}", "{\"results\":{}}")) {
            assertFalse(beyond.detect(json(body)).isPresent(), body);
            assertFalse(lot1.detect(json(body)).isPresent(), body);
        }
    }

    @Test
    void aFlatListWithNamesIsReadAsBeyond() throws IOException {
        // Instruct-ERIC and EGI publish flat lists with a name and a webpage per service
        assertTrue(beyond.detect(json("{\"provider\":{},\"total\":1,\"results\":[{\"id\":\"x\",\"name\":\"N\",\"webpage\":\"https://x\"}]}")).isPresent());
    }

    @Test
    void probeUrlsKeepAnExistingQuery() {
        assertEquals("https://x/services?types=Service&from=0&quantity=" + Lot1Adapter.PAGE_SIZE,
                lot1.probeUrls(java.net.URI.create("https://x/services?types=Service")).get(0).toString());
        assertEquals("https://x/services?from=0&quantity=" + Lot1Adapter.PAGE_SIZE,
                lot1.probeUrls(java.net.URI.create("https://x/services")).get(0).toString());
        assertEquals("https://x/api/service/all", beyond.probeUrls(java.net.URI.create("https://x/api")).get(0).toString());
    }
}
