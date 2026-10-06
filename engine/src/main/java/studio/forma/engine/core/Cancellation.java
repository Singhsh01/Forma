package studio.forma.engine.core;

/** Cooperative cancellation token checked at rule steps and loop boundaries. */
public final class Cancellation {
    private volatile boolean cancelled;
    private final long deadlineNanos;

    public Cancellation() {
        this(0);
    }

    /** @param timeoutMillis 0 for no deadline */
    public Cancellation(long timeoutMillis) {
        this.deadlineNanos = timeoutMillis > 0 ? System.nanoTime() + timeoutMillis * 1_000_000L : 0;
    }

    public static Cancellation none() {
        return new Cancellation();
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled || (deadlineNanos != 0 && System.nanoTime() > deadlineNanos);
    }

    public void check() {
        if (cancelled) throw new CancelledException("generation cancelled");
        if (deadlineNanos != 0 && System.nanoTime() > deadlineNanos)
            throw new CancelledException("generation exceeded its time limit");
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException(String msg) {
            super(msg);
        }
    }
}
