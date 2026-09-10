package uk.ac.ebi.zooma2.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.http.client.methods.HttpPost;
import org.junit.jupiter.api.Test;

/** A POST in flight must be aborted when the request is cancelled, exactly like a GET (issue #27). */
class CancellationWatcherTest {

    @Test
    void abortsThePostAndInterruptsTheCallerWhenTheFlagIsSet() throws Exception {
        try (var scope = RequestCancellation.acquire()) {
            HttpPost post = new HttpPost("http://localhost:1/api/v2/tag_text");
            Thread watcher = CachedHttpClient.abortOnCancellation(post);
            assertFalse(post.isAborted());

            scope.flag().set(true);
            // The watcher interrupts this thread, so a blocking join would throw: spin instead.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!post.isAborted() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(post.isAborted(), "request aborted");
            while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(Thread.interrupted(), "calling thread interrupted (and cleared here)");
            watcher.join(5000);
        }
    }

    @Test
    void noWatcherWithoutARequestFlag() {
        assertNull(RequestCancellation.getFlag());
        assertNull(CachedHttpClient.abortOnCancellation(new HttpPost("http://localhost:1/x")));
    }

    @Test
    void watcherStopsQuietlyWhenTheRequestCompletesFirst() throws Exception {
        try (var scope = RequestCancellation.acquire()) {
            HttpPost post = new HttpPost("http://localhost:1/x");
            Thread watcher = CachedHttpClient.abortOnCancellation(post);
            watcher.interrupt();
            watcher.join(5000);
            assertFalse(watcher.isAlive());
            assertFalse(post.isAborted());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }
}
