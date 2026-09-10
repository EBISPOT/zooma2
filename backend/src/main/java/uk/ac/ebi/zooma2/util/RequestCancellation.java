package uk.ac.ebi.zooma2.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-request cancellation flag propagated across virtual thread boundaries via ThreadLocal.
 * When the streaming client disconnects, the flag is set to true and all in-flight HTTP
 * calls abort immediately via {@link CachedHttpClient}'s watcher thread.
 *
 * <p>ThreadLocal values are not inherited by virtual threads created with
 * {@code Executors.newVirtualThreadPerTaskExecutor()}, so callers must explicitly
 * propagate the flag: capture it on the parent thread with {@link #getFlag()}, then
 * call {@link #setFlag(AtomicBoolean)} as the first line of each submitted lambda.
 *
 * <p>Layers nest: an endpoint creates the flag for the request, and the batch
 * passes it runs (shallow, then deep) share it. Only the layer that created the
 * flag may remove it, otherwise an inner pass would detach every later pass from
 * the request's heartbeat. {@link #acquire()} encodes that: it returns a
 * {@link Scope} whose {@link Scope#close()} removes the flag only if the scope
 * created it.
 */
public class RequestCancellation {

    private static final ThreadLocal<AtomicBoolean> flag = new ThreadLocal<>();

    /** The current thread's cancellation flag, released when the creating layer is done. */
    public static final class Scope implements AutoCloseable {
        private final AtomicBoolean flag;
        private final boolean owner;

        private Scope(AtomicBoolean flag, boolean owner) {
            this.flag = flag;
            this.owner = owner;
        }

        public AtomicBoolean flag() {
            return flag;
        }

        /** True if this scope created the flag (and will remove it on close). */
        public boolean isOwner() {
            return owner;
        }

        @Override
        public void close() {
            if (owner) {
                RequestCancellation.flag.remove();
            }
        }
    }

    /**
     * Joins the flag already registered on the current thread, or creates and
     * registers one if there is none. Use in a try-with-resources: closing the
     * returned scope removes the flag only if this call created it.
     */
    public static Scope acquire() {
        var existing = flag.get();
        if (existing != null) return new Scope(existing, false);
        var f = new AtomicBoolean(false);
        flag.set(f);
        return new Scope(f, true);
    }

    /** Registers an existing flag on the current thread. Call inside each virtual thread lambda. */
    public static void setFlag(AtomicBoolean f) {
        flag.set(f);
    }

    /** Returns the cancellation flag registered on the current thread, or {@code null} if none. */
    public static AtomicBoolean getFlag() {
        return flag.get();
    }

    /** Returns {@code true} if a flag is registered on the current thread and has been set. */
    public static boolean isCancelled() {
        var f = flag.get();
        return f != null && f.get();
    }

    private RequestCancellation() {}
}
