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
 */
public class RequestCancellation {

    private static final ThreadLocal<AtomicBoolean> flag = new ThreadLocal<>();

    /**
     * Creates a new cancellation flag and registers it on the current thread.
     * If a flag already exists on the current thread (set by an outer caller), returns
     * it unchanged so all layers share the same {@link AtomicBoolean} instance.
     */
    public static AtomicBoolean newFlag() {
        var existing = flag.get();
        if (existing != null) return existing;
        var f = new AtomicBoolean(false);
        flag.set(f);
        return f;
    }

    /** Registers an existing flag on the current thread. Call inside each virtual thread lambda. */
    public static void setFlag(AtomicBoolean f) {
        flag.set(f);
    }

    /** Returns the cancellation flag registered on the current thread, or {@code null} if none. */
    public static AtomicBoolean getFlag() {
        return flag.get();
    }

    /** Removes the flag from the current thread. Call on the parent thread after the request ends. */
    public static void clearFlag() {
        flag.remove();
    }

    /** Returns {@code true} if a flag is registered on the current thread and has been set. */
    public static boolean isCancelled() {
        var f = flag.get();
        return f != null && f.get();
    }

    private RequestCancellation() {}
}
