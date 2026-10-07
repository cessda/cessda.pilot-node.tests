package eu.cessda.pilotnode.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

/** The fake node answers a malformed numeric parameter the way a real server would: HTTP 400. */
class FakeNodeTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private int get(FakeNode node, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(node.base() + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void aMalformedNumberIsABadRequest() throws Exception {
        try (var node = new FakeNode()) {
            node.lot1("/services", FakeNode.lot1Services(3, FakeNode.V2_CATEGORY));

            assertEquals(200, get(node, "/services?from=0&quantity=2"));
            assertEquals(200, get(node, "/services"));
            assertEquals(400, get(node, "/services?from=abc"));
            assertEquals(400, get(node, "/services?quantity=1.5"));
        }
    }
}
