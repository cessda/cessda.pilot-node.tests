package eu.cessda.pilotnode.catalogue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A node's catalogue endpoints, served locally: Lot-1 lists, a Beyond list, a DCAT feed and failures. */
public final class FakeNode implements AutoCloseable {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** A response the fake node gives for a path. */
    public record Reply(int status, String body) {}

    private final HttpServer server;
    private final Map<String, Function<Map<String, String>, Reply>> routes = new ConcurrentHashMap<>();
    /** Every request, as "path?query". */
    public final List<String> requests = new CopyOnWriteArrayList<>();

    public FakeNode() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void route(String path, Function<Map<String, String>, Reply> handler) {
        routes.put(path, handler);
    }

    public void json(String path, JsonNode body) {
        routes.put(path, q -> new Reply(200, body.toString()));
    }

    public void status(String path, int status) {
        routes.put(path, q -> new Reply(status, "error"));
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String raw = ex.getRequestURI().getRawQuery();
        requests.add(path + (raw == null ? "" : "?" + URLDecoder.decode(raw, StandardCharsets.UTF_8)));
        Function<Map<String, String>, Reply> handler = routes.get(path);
        Reply reply = handler == null ? new Reply(404, "not found") : handler.apply(query(raw));
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new ConcurrentHashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int i = pair.indexOf('=');
                if (i > 0) {
                    out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    /** A service that conforms to the Lot-1 model, as the {@code service} part of a bundle. */
    public static ObjectNode lot1Service(String id, String name, String category) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("id", id);
        s.put("name", name);
        s.put("webpage", "https://example.org/" + id);
        s.put("description", "About " + name);
        s.putArray("scientificDomains").addObject().put("scientificDomain", "scientific_domain-generic");
        s.putArray("categories").addObject().put("category", category);
        s.putArray("targetUsers").add("target_user-researchers");
        s.putArray("languageAvailabilities").add("en");
        s.put("trl", "trl-8");
        s.put("orderType", "order_type-open_access");
        s.put("helpdeskEmail", "help@example.org");
        s.putArray("tags").add("one");
        return s;
    }

    public static final String V2_CATEGORY = "service_classification-compute_services";
    public static final String V1_CATEGORY = "category-processing_and_analysis-data_analysis";

    /** {@code {total, from, to, results: [{id, service}]}} for the given services, as a Lot-1 catalogue serves it. */
    public static ObjectNode lot1Page(List<ObjectNode> all, int from, int quantity) {
        ArrayNode results = MAPPER.createArrayNode();
        for (int i = from; i < Math.min(all.size(), from + quantity); i++) {
            ObjectNode bundle = results.addObject();
            bundle.put("id", all.get(i).path("id").asText());
            bundle.set("service", all.get(i));
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("total", all.size());
        root.put("from", from);
        root.put("to", from + results.size());
        root.set("results", results);
        return root;
    }

    /** Serves {@code all} at {@code path}, honouring {@code from} and {@code quantity}. */
    public void lot1(String path, List<ObjectNode> all) {
        routes.put(path, q -> new Reply(200, lot1Page(all, Integer.parseInt(q.getOrDefault("from", "0")),
                Integer.parseInt(q.getOrDefault("quantity", "10"))).toString()));
    }

    public static List<ObjectNode> lot1Services(int count, String category) {
        List<ObjectNode> list = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(lot1Service("svc-" + i, "Service " + i, category));
        }
        return list;
    }

    /** The EOSC Beyond Resource Catalogue's list. */
    public static ObjectNode beyondList(String... names) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode results = root.putArray("results");
        for (String n : names) {
            results.addObject().put("id", "service/" + n).put("name", n).put("webpage", "https://example.org/" + n);
        }
        root.put("total", names.length);
        root.put("from", 0);
        root.put("to", names.length);
        root.putArray("facets");
        return root;
    }

    /** A DCAT catalogue feed, which no adapter reads. */
    public static ObjectNode dcatFeed() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("@context").put("dcat", "http://www.w3.org/ns/dcat#");
        root.put("@type", "dcat:Catalog");
        root.putArray("dcat:resource").addObject().put("@id", "urn:x");
        return root;
    }

    /** A capability row as {@code endpoint_report.json} has it. */
    public static ObjectNode row(String type, String endpoint, String declaredStatus) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("capability_type", type);
        r.put("endpoint", endpoint);
        r.put("declared_status", declaredStatus);
        return r;
    }

    public static ObjectNode report(String nodePid, ObjectNode... rows) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("node_name", "N");
        r.put("node_pid", nodePid);
        ArrayNode caps = r.putArray("capabilities");
        for (ObjectNode row : rows) {
            caps.add(row);
        }
        return r;
    }
}
