package studio.forma.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import studio.forma.engine.io.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Starts the real HTTP server on an ephemeral port and drives it like the web app does. */
class HttpApiTest {
    static FormaServer server;
    static HttpClient http;
    static String base;
    static Path web;

    @BeforeAll
    static void start() throws Exception {
        web = Files.createTempDirectory("forma-web");
        Files.writeString(web.resolve("index.html"), "<!doctype html><title>FORMA test shell</title>");
        server = new FormaServer(new JobManager(2, 4, 16, 60_000), web);
        server.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + server.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(String json) {
        return (Map<String, Object>) Json.parse(json);
    }

    static Map<String, Object> waitFor(String jobId) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            Map<String, Object> s = obj(get("/api/jobs/" + jobId).body());
            String st = (String) s.get("status");
            if (st.equals("completed") || st.equals("failed") || st.equals("cancelled")) return s;
            Thread.sleep(50);
        }
        fail("job " + jobId + " did not finish");
        return null;
    }

    @Test
    void healthAndPresets() throws Exception {
        var h = get("/api/health");
        assertEquals(200, h.statusCode());
        assertEquals("ok", obj(h.body()).get("status"));
        var p = obj(get("/api/presets").body());
        @SuppressWarnings("unchecked") List<Map<String, Object>> presets = (List<Map<String, Object>>) p.get("presets");
        assertEquals(List.of("library", "cliffside", "gardens", "cathedral", "canal", "organic", "escher"),
            presets.stream().map(m -> (String) m.get("id")).toList());
    }

    @Test
    void generateThenFetchEveryResult() throws Exception {
        var r = post("/api/jobs/generate", "{\"config\":{\"preset\":\"escher\",\"seed\":4,\"params\":{\"levels\":4}}}");
        assertEquals(202, r.statusCode(), r.body());
        String id = (String) obj(r.body()).get("jobId");
        assertEquals("completed", waitFor(id).get("status"));
        for (String res : List.of("scene", "replay", "inspector", "config")) {
            var x = get("/api/jobs/" + id + "/" + res);
            assertEquals(200, x.statusCode(), res);
            assertNotNull(Json.parse(x.body()), res + " is valid JSON");
        }
        Map<String, Object> cfg = obj(get("/api/jobs/" + id + "/config").body());
        assertEquals("escher", cfg.get("preset"));
        assertEquals(4.0, ((Number) cfg.get("seed")).doubleValue());
        assertEquals(404, get("/api/jobs/" + id + "/refinement").statusCode(), "a generation job has no refinement");
    }

    @Test
    void eventStreamEndsWithDone() throws Exception {
        var r = post("/api/jobs/generate", "{\"config\":{\"preset\":\"canal\",\"seed\":8}}");
        String id = (String) obj(r.body()).get("jobId");
        var ev = get("/api/jobs/" + id + "/events");
        assertEquals(200, ev.statusCode());
        assertTrue(ev.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
        assertTrue(ev.body().contains("event: done"), ev.body());
    }

    @Test
    void refineAgainstABaseJob() throws Exception {
        String id = (String) obj(post("/api/jobs/generate", "{\"config\":{\"preset\":\"gardens\",\"seed\":2}}").body()).get("jobId");
        waitFor(id);
        var r = post("/api/jobs/refine", "{\"baseJobId\":\"" + id + "\",\"refine\":{\"iterations\":250,\"temperature\":0.3,\"mode\":\"mcmc\",\"mcmcSeed\":3,\"keep\":\"final\"}}");
        assertEquals(202, r.statusCode(), r.body());
        String rid = (String) obj(r.body()).get("jobId");
        assertEquals("completed", waitFor(rid).get("status"));
        Map<String, Object> ref = obj(get("/api/jobs/" + rid + "/refinement").body());
        assertEquals("final", ref.get("kept"));
        assertEquals(id, ref.get("baseJobId"));
    }

    @Test
    void errorsAreJsonWithStatusCodes() throws Exception {
        var bad = post("/api/jobs/generate", "{\"config\":{\"preset\":\"atlantis\",\"seed\":1}}");
        assertEquals(400, bad.statusCode());
        assertTrue(((String) obj(bad.body()).get("error")).contains("atlantis"));
        assertEquals(400, post("/api/jobs/generate", "{not json").statusCode());
        var unknownParam = post("/api/jobs/generate", "{\"config\":{\"preset\":\"canal\",\"seed\":1,\"params\":{\"towers\":3}}}");
        assertEquals(400, unknownParam.statusCode());
        assertTrue(((String) obj(unknownParam.body()).get("error")).contains("unknown parameter 'towers'"));
        assertEquals(404, get("/api/jobs/does-not-exist").statusCode());
        assertEquals(404, get("/api/nowhere").statusCode());
        var rules = obj(post("/api/validate-rules", "{\"preset\":\"canal\",\"overrides\":{\"growth\":\"sequence g\\n  one x\\n    rule r \\\"WW\\\" -> \\\"W\\\"\"}}").body());
        assertEquals(false, rules.get("ok"));
        assertNotNull(rules.get("error"));
    }

    @Test
    void servesTheWebAppWithSpaFallback() throws Exception {
        var root = get("/");
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("FORMA test shell"));
        var deep = get("/studio");
        assertEquals(200, deep.statusCode(), "client routes fall back to index.html");
        // path traversal never escapes the web root
        var trav = get("/%2e%2e/%2e%2e/%2e%2e/etc/passwd");
        assertFalse(trav.body().contains("root:"), "no file outside the web root is served");
    }
}
