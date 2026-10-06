package studio.forma.server;

import studio.forma.engine.GenerationConfig;
import studio.forma.engine.GenerationResult;
import studio.forma.engine.core.Cancellation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** One generation or refinement job and its observable state. Thread-safe via synchronized methods. */
public final class Job {
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    public record Event(long seq, String type, String json) {}

    public final String id;
    public final String type; // "generate" | "refine"
    public final GenerationConfig config;
    public final long createdAt = System.currentTimeMillis();
    public final Cancellation cancellation;
    public final String baseJobId;

    private Status status = Status.QUEUED;
    private double fraction;
    private String stage = "queued";
    private String message = "waiting for a worker";
    private String error;
    private long startedAt, finishedAt;
    private GenerationResult result;
    private String refinementJson;
    private String sceneJson, replayJson, inspectorJson;
    private final List<Event> events = new ArrayList<>();
    private final List<Consumer<Event>> listeners = new ArrayList<>();
    private long seq;
    private long lastProgressEmit;

    public Job(String id, String type, GenerationConfig config, long timeoutMillis, String baseJobId) {
        this.id = id;
        this.type = type;
        this.config = config;
        this.cancellation = new Cancellation(timeoutMillis);
        this.baseJobId = baseJobId;
    }

    public synchronized Status status() { return status; }
    public synchronized GenerationResult result() { return result; }
    public synchronized String error() { return error; }
    public synchronized String refinementJson() { return refinementJson; }

    public synchronized boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    synchronized void start() {
        status = Status.RUNNING;
        startedAt = System.currentTimeMillis();
        stage = "starting";
        message = "started";
        emit("status", statusJson());
    }

    /** Progress is throttled to one event per 80 ms (always emitted on stage changes). */
    synchronized void progress(String stageId, double f, String msg) {
        boolean stageChanged = !stageId.equals(stage);
        fraction = Math.max(fraction, Math.min(0.995, f));
        stage = stageId;
        message = msg;
        long now = System.currentTimeMillis();
        if (stageChanged || now - lastProgressEmit >= 80) {
            lastProgressEmit = now;
            emit("progress", statusJson());
        }
    }

    synchronized void complete(GenerationResult r, String refinement) {
        result = r;
        refinementJson = refinement;
        status = Status.COMPLETED;
        fraction = 1;
        stage = "done";
        message = "completed";
        finishedAt = System.currentTimeMillis();
        emit("done", statusJson());
    }

    synchronized void fail(String err) {
        status = Status.FAILED;
        error = err;
        stage = "failed";
        message = err;
        finishedAt = System.currentTimeMillis();
        emit("done", statusJson());
    }

    synchronized void cancelled(String why) {
        status = Status.CANCELLED;
        stage = "cancelled";
        message = why;
        finishedAt = System.currentTimeMillis();
        emit("done", statusJson());
    }

    private void emit(String type, String json) {
        Event e = new Event(++seq, type, json);
        events.add(e);
        if (events.size() > 200) events.remove(0);
        for (Consumer<Event> l : List.copyOf(listeners)) {
            try {
                l.accept(e);
            } catch (RuntimeException ignored) {
                listeners.remove(l);
            }
        }
    }

    /** Registers a listener and returns events after {@code afterSeq} that it missed (for reconnects). */
    synchronized List<Event> subscribe(Consumer<Event> l, long afterSeq) {
        listeners.add(l);
        List<Event> missed = new ArrayList<>();
        for (Event e : events) if (e.seq() > afterSeq) missed.add(e);
        return missed;
    }

    synchronized void unsubscribe(Consumer<Event> l) {
        listeners.remove(l);
    }

    public synchronized String statusJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"jobId\":\"").append(id).append("\",\"type\":\"").append(type)
            .append("\",\"status\":\"").append(status.name().toLowerCase())
            .append("\",\"stage\":").append(q(stage))
            .append(",\"fraction\":").append(Math.round(fraction * 1000) / 1000.0)
            .append(",\"message\":").append(q(message))
            .append(",\"preset\":").append(q(config.preset()))
            .append(",\"seed\":").append(config.seed())
            .append(",\"createdAt\":").append(createdAt);
        if (baseJobId != null) sb.append(",\"baseJobId\":").append(q(baseJobId));
        if (startedAt > 0) sb.append(",\"startedAt\":").append(startedAt);
        if (finishedAt > 0) sb.append(",\"finishedAt\":").append(finishedAt).append(",\"elapsedMs\":").append(finishedAt - Math.max(startedAt, createdAt));
        if (error != null) sb.append(",\"error\":").append(q(error));
        sb.append('}');
        return sb.toString();
    }

    synchronized String cachedScene(java.util.function.Supplier<String> make) {
        if (sceneJson == null) sceneJson = make.get();
        return sceneJson;
    }

    synchronized String cachedReplay(java.util.function.Supplier<String> make) {
        if (replayJson == null) replayJson = make.get();
        return replayJson;
    }

    synchronized String cachedInspector(java.util.function.Supplier<String> make) {
        if (inspectorJson == null) inspectorJson = make.get();
        return inspectorJson;
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
