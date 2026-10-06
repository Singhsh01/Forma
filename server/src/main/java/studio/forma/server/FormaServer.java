package studio.forma.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import studio.forma.engine.GenerationConfig;
import studio.forma.engine.Version;
import studio.forma.engine.io.Json;
import studio.forma.engine.io.Serializers;
import studio.forma.engine.presets.Params;
import studio.forma.engine.presets.Preset;
import studio.forma.engine.presets.PresetRegistry;
import studio.forma.engine.rules.RuleValidationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * FORMA HTTP API on the JDK's built-in server (no framework, no external dependencies).
 *
 * <p>Configuration (all optional): {@code --port N} / {@code FORMA_PORT} (default 8080),
 * {@code --host H} / {@code FORMA_HOST} (default 127.0.0.1), {@code --web DIR} / {@code FORMA_WEB}
 * (built web app to serve; default {@code web/dist} if present).
 */
public final class FormaServer {
    private static final int MAX_BODY = 256 * 1024;

    private final JobManager jobs;
    private final Path web;
    private HttpServer server;

    public FormaServer(JobManager jobs, Path web) {
        this.jobs = jobs;
        this.web = web;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(opt(args, "--port", System.getenv().getOrDefault("FORMA_PORT", "8080")));
        String host = opt(args, "--host", System.getenv().getOrDefault("FORMA_HOST", "127.0.0.1"));
        String webOpt = opt(args, "--web", System.getenv().get("FORMA_WEB"));
        Path web = null;
        if (webOpt != null) web = Path.of(webOpt);
        else for (String c : List.of("web/dist", "../web/dist")) if (Files.isDirectory(Path.of(c))) { web = Path.of(c); break; }
        if (web != null && !Files.isRegularFile(web.resolve("index.html"))) {
            System.err.println("[forma] note: " + web.toAbsolutePath() + " has no index.html; serving the API only");
            web = null;
        }
        FormaServer s = new FormaServer(new JobManager(2, 6, 24, 90_000), web);
        try {
            s.start(host, port);
        } catch (BindException e) {
            System.err.println("[forma] port " + port + " is already in use. Stop the other process or start with --port <other> (or FORMA_PORT).");
            System.exit(1);
        }
        System.out.println("[forma] " + Version.ENGINE + " listening on http://" + host + ":" + s.port()
            + (web != null ? "  (serving web app from " + web.toAbsolutePath().normalize() + ")" : "  (API only; run the web dev server separately)"));
    }

    private static String opt(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return def;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 64);
        server.setExecutor(Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "forma-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        jobs.shutdown();
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) {
                cors(ex);
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            if (!path.startsWith("/api/")) {
                serveStatic(ex, path);
                return;
            }
            cors(ex);
            String[] seg = path.substring(5).split("/");
            switch (seg[0]) {
                case "health" -> json(ex, 200, "{\"status\":\"ok\",\"generatorVersion\":\"" + Version.ENGINE + "\",\"activeJobs\":" + jobs.activeCount() + "}");
                case "presets" -> json(ex, 200, Serializers.presets());
                case "programs" -> programs(ex);
                case "validate-rules" -> validateRules(ex);
                case "jobs" -> jobsRoute(ex, method, seg);
                default -> error(ex, 404, "no such endpoint: " + path);
            }
        } catch (JobManager.Rejected e) {
            error(ex, e.status, e.getMessage());
        } catch (RuleValidationException e) {
            error(ex, 400, e.getMessage());
        } catch (IllegalArgumentException e) {
            error(ex, 400, e.getMessage());
        } catch (IOException e) {
            ex.close();
        } catch (RuntimeException e) {
            error(ex, 500, "internal error: " + e);
        }
    }

    private void jobsRoute(HttpExchange ex, String method, String[] seg) throws IOException {
        if (seg.length == 2 && method.equals("POST")) {
            Map<String, Object> body = body(ex);
            switch (seg[1]) {
                case "generate" -> {
                    Object c = body.containsKey("config") ? body.get("config") : body;
                    GenerationConfig cfg = Serializers.parseConfig(asMap(c, "config"));
                    Job j = jobs.submitGenerate(cfg);
                    json(ex, 202, j.statusJson());
                }
                case "refine" -> {
                    String base = body.get("baseJobId") instanceof String s ? s : null;
                    GenerationConfig cfg = body.get("config") instanceof Map<?, ?> ? Serializers.parseConfig(asMap(body.get("config"), "config")) : null;
                    Map<String, Object> r = body.get("refine") instanceof Map<?, ?> ? asMap(body.get("refine"), "refine") : Map.of();
                    Map<String, Double> weights = new LinkedHashMap<>();
                    if (r.get("weights") instanceof Map<?, ?> wm)
                        for (var e : wm.entrySet()) if (e.getValue() instanceof Double d) weights.put((String) e.getKey(), Math.max(0, Math.min(5, d)));
                    boolean anneal = "anneal".equals(r.get("mode"));
                    JobManager.RefineSpec spec = new JobManager.RefineSpec(
                        (int) num(r, "iterations", 1500), num(r, "temperature", 0.35), num(r, "temperatureEnd", 0.02), anneal,
                        weights, (long) num(r, "mcmcSeed", 1), !"final".equals(r.get("keep")));
                    if (base == null && cfg == null) throw new IllegalArgumentException("send baseJobId or config");
                    Job j = jobs.submitRefine(cfg, base, spec);
                    json(ex, 202, j.statusJson());
                }
                default -> error(ex, 404, "unknown job type " + seg[1]);
            }
            return;
        }
        if (seg.length < 2) {
            error(ex, 404, "missing job id");
            return;
        }
        Job j = jobs.get(seg[1]);
        if (j == null) {
            error(ex, 404, "job " + seg[1] + " not found (it may have expired)");
            return;
        }
        if (seg.length == 2) {
            if (method.equals("DELETE")) {
                jobs.cancel(j.id);
                json(ex, 202, j.statusJson());
            } else json(ex, 200, j.statusJson());
            return;
        }
        switch (seg[2]) {
            case "events" -> sse(ex, j);
            case "scene", "replay", "inspector", "refinement", "config" -> {
                if (j.status() != Job.Status.COMPLETED) {
                    error(ex, 409, "job " + j.id + " is " + j.status().name().toLowerCase() + "; results are available once it completes");
                    return;
                }
                var r = j.result();
                String out = switch (seg[2]) {
                    case "scene" -> j.cachedScene(() -> Serializers.scene(r, j.id));
                    case "replay" -> j.cachedReplay(() -> Serializers.replay(r));
                    case "inspector" -> j.cachedInspector(() -> Serializers.inspector(r));
                    case "config" -> {
                        Json.Writer w = new Json.Writer();
                        Serializers.config(w, j.config);
                        yield w.toString();
                    }
                    default -> j.refinementJson();
                };
                if (out == null) error(ex, 404, "this job has no refinement data");
                else json(ex, 200, out);
            }
            default -> error(ex, 404, "unknown job resource " + seg[2]);
        }
    }

    private void programs(HttpExchange ex) throws IOException {
        Map<String, Object> body = body(ex);
        Preset p = PresetRegistry.get(String.valueOf(body.get("preset")));
        Map<String, Double> params = new LinkedHashMap<>();
        if (body.get("params") instanceof Map<?, ?> pm) for (var e : pm.entrySet()) if (e.getValue() instanceof Double d) params.put((String) e.getKey(), d);
        Params resolved = Params.resolve(p.params(), params);
        Json.Writer w = new Json.Writer();
        w.object().key("programs").object();
        for (String id : p.programIds()) w.field(id, p.defaultProgram(id, resolved));
        w.end().end();
        json(ex, 200, w.toString());
    }

    private void validateRules(HttpExchange ex) throws IOException {
        Map<String, Object> body = body(ex);
        Preset p = PresetRegistry.get(String.valueOf(body.get("preset")));
        Map<String, String> ov = new LinkedHashMap<>();
        if (body.get("overrides") instanceof Map<?, ?> m) for (var e : m.entrySet()) ov.put((String) e.getKey(), String.valueOf(e.getValue()));
        try {
            studio.forma.engine.Generator.validateOverrides(p, ov);
            json(ex, 200, "{\"ok\":true}");
        } catch (RuleValidationException e) {
            json(ex, 200, "{\"ok\":false,\"error\":" + Job.q(e.getMessage()) + "}");
        }
    }

    // ------------------------------------------------------------------ SSE

    private void sse(HttpExchange ex, Job j) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        long after = 0;
        String last = ex.getRequestHeaders().getFirst("Last-Event-ID");
        if (last != null) try { after = Long.parseLong(last.trim()); } catch (NumberFormatException ignored) {}
        LinkedBlockingQueue<Job.Event> q = new LinkedBlockingQueue<>();
        java.util.function.Consumer<Job.Event> l = q::add;
        List<Job.Event> missed = j.subscribe(l, after);
        try {
            write(os, "retry: 1500\n\n");
            write(os, "event: status\ndata: " + j.statusJson() + "\n\n");
            for (Job.Event e : missed) writeEvent(os, e);
            if (j.isTerminal()) {
                write(os, "event: done\ndata: " + j.statusJson() + "\n\n");
                return;
            }
            long deadline = System.currentTimeMillis() + 10 * 60_000;
            while (System.currentTimeMillis() < deadline) {
                Job.Event e = q.poll(15, TimeUnit.SECONDS);
                if (e == null) {
                    write(os, ": keep-alive\n\n");
                    continue;
                }
                // batch: drain whatever else is queued, keep only the newest progress
                List<Job.Event> batch = new java.util.ArrayList<>();
                batch.add(e);
                q.drainTo(batch);
                Job.Event lastProgress = null;
                for (Job.Event b : batch) {
                    if (b.type().equals("progress")) lastProgress = b;
                    else {
                        if (lastProgress != null) { writeEvent(os, lastProgress); lastProgress = null; }
                        writeEvent(os, b);
                    }
                }
                if (lastProgress != null) writeEvent(os, lastProgress);
                if (j.isTerminal() && q.isEmpty()) break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // client went away
        } finally {
            j.unsubscribe(l);
            try { os.close(); } catch (IOException ignored) {}
        }
    }

    private static void writeEvent(OutputStream os, Job.Event e) throws IOException {
        write(os, "id: " + e.seq() + "\nevent: " + e.type() + "\ndata: " + e.json() + "\n\n");
    }

    private static void write(OutputStream os, String s) throws IOException {
        os.write(s.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    // ------------------------------------------------------------------ static files

    private static final Map<String, String> MIME = Map.ofEntries(
        Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
        Map.entry("css", "text/css; charset=utf-8"), Map.entry("json", "application/json"),
        Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
        Map.entry("webp", "image/webp"), Map.entry("woff2", "font/woff2"), Map.entry("woff", "font/woff"),
        Map.entry("ico", "image/x-icon"), Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("glb", "model/gltf-binary"),
        Map.entry("webm", "video/webm"), Map.entry("mp4", "video/mp4"));

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (web == null) {
            text(ex, 404, "FORMA API server. The web app is not built: run the web dev server (npm run dev in web/) or build it (npm run build).");
            return;
        }
        Path root = web.toAbsolutePath().normalize();
        Path f = root.resolve(path.substring(1)).normalize();
        if (!f.startsWith(root)) {
            text(ex, 403, "forbidden");
            return;
        }
        if (Files.isDirectory(f)) f = f.resolve("index.html");
        if (!Files.isRegularFile(f)) {
            boolean looksLikeFile = path.substring(path.lastIndexOf('/') + 1).contains(".");
            if (looksLikeFile) {
                text(ex, 404, "not found: " + path);
                return;
            }
            f = root.resolve("index.html"); // single-page app fallback
        }
        String name = f.getFileName().toString();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        byte[] data = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
        if (path.startsWith("/assets/")) ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        send(ex, 200, data, ext.equals("html") || ext.equals("js") || ext.equals("css") || ext.equals("json") || ext.equals("svg"));
    }

    // ------------------------------------------------------------------ helpers

    private static void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && (origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:"))) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Last-Event-ID");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String what) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("'" + what + "' must be a JSON object");
        return (Map<String, Object>) o;
    }

    private static double num(Map<String, Object> m, String k, double def) {
        Object o = m.get(k);
        if (o == null) return def;
        if (o instanceof Double d) return d;
        throw new IllegalArgumentException("'" + k + "' must be a number");
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) throw new IllegalArgumentException("request body too large (limit " + MAX_BODY / 1024 + " KB)");
                bo.write(buf, 0, n);
            }
            String s = bo.toString(StandardCharsets.UTF_8);
            if (s.isBlank()) return Map.of();
            return Json.parseObject(s);
        }
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, body.getBytes(StandardCharsets.UTF_8), true);
    }

    private static void text(HttpExchange ex, int status, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        send(ex, status, body.getBytes(StandardCharsets.UTF_8), false);
    }

    private static void error(HttpExchange ex, int status, String msg) throws IOException {
        json(ex, status, "{\"error\":" + Job.q(msg) + ",\"status\":" + status + "}");
    }

    private static void send(HttpExchange ex, int status, byte[] data, boolean compressible) throws IOException {
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (compressible && data.length > 1024 && ae != null && ae.contains("gzip")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length / 4);
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
                gz.write(data);
            }
            data = bo.toByteArray();
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        ex.getResponseHeaders().set("Vary", "Accept-Encoding");
        ex.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        if (data.length > 0) try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
        ex.close();
    }
}
