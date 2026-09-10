package uk.ac.ebi.zooma2.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs independent blocking calls (OLS requests) concurrently on virtual threads
 * and returns their results in the order the calls were given, so callers can
 * merge deterministically. The request's cancellation flag and diagnostics sink
 * are carried into each task, because virtual threads inherit neither. A single
 * call runs inline.
 */
public final class ConcurrentCalls {

    private ConcurrentCalls() {
    }

    public static <T> List<T> run(List<Callable<T>> calls) {
        List<T> results = new ArrayList<>(calls.size());
        if (calls.size() == 1) {
            try {
                results.add(calls.get(0).call());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return results;
        }
        final AtomicBoolean cancelFlag = RequestCancellation.getFlag();
        final List<String> sink = Diagnostics.current();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(calls.size());
            for (Callable<T> call : calls) {
                futures.add(executor.submit(() -> {
                    if (cancelFlag != null) RequestCancellation.setFlag(cancelFlag);
                    Diagnostics.attach(sink);
                    return call.call();
                }));
            }
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    futures.forEach(f -> f.cancel(true));
                    throw new RuntimeException("Interrupted while waiting for concurrent calls", e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException re) throw re;
                    throw new RuntimeException(e.getCause());
                }
            }
        }
        return results;
    }
}
