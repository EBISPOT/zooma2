package uk.ac.ebi.zooma2.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-property warning sink. Anything that degrades a mapping without failing
 * it outright (an OLS endpoint unavailable, terms that could not be resolved,
 * an unreadable response) reports here, and the property's response carries
 * the messages, so an outage produces a visibly degraded answer rather than a
 * clean, empty one.
 *
 * <p>The sink is thread-local. {@code StringMapper} opens one for the property
 * it is mapping and drains it into warning results; {@code AnnotationEngine}
 * attaches the same list to the matcher threads it spawns (virtual threads do
 * not inherit thread-locals). Code running with no sink just logs.
 */
public final class Diagnostics {

    private static final ThreadLocal<List<String>> SINK = new ThreadLocal<>();

    private Diagnostics() {
    }

    /** Opens a fresh sink on the current thread and returns it. Pair with {@link #end()}. */
    public static List<String> begin() {
        List<String> sink = Collections.synchronizedList(new ArrayList<>());
        SINK.set(sink);
        return sink;
    }

    public static void end() {
        SINK.remove();
    }

    /** The current thread's sink, to hand to child threads via {@link #attach}; {@code null} if none. */
    public static List<String> current() {
        return SINK.get();
    }

    public static void attach(List<String> sink) {
        if (sink != null) SINK.set(sink);
    }

    /** Records a warning for the property being mapped (deduplicated) and logs it. */
    public static void warn(String message) {
        if (message == null || message.isBlank()) return;
        List<String> sink = SINK.get();
        if (sink != null) {
            synchronized (sink) {
                if (!sink.contains(message)) sink.add(message);
            }
        }
        System.err.println("Warning: " + message);
    }
}
