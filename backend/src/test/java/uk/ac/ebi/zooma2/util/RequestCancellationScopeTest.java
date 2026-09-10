package uk.ac.ebi.zooma2.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Only the layer that created the request's cancellation flag may remove it (issue #18, point 1). */
class RequestCancellationScopeTest {

    @Test
    void innerScopesJoinTheOuterFlagAndDoNotClearItOnClose() {
        assertNull(RequestCancellation.getFlag());
        try (var request = RequestCancellation.acquire()) {
            assertTrue(request.isOwner());
            assertSame(request.flag(), RequestCancellation.getFlag());

            try (var shallowPass = RequestCancellation.acquire()) {
                assertFalse(shallowPass.isOwner());
                assertSame(request.flag(), shallowPass.flag());
            }
            // The old clearFlag() here detached every later pass from the heartbeat
            assertSame(request.flag(), RequestCancellation.getFlag(), "flag survives the inner pass");

            try (var deepPass = RequestCancellation.acquire()) {
                assertSame(request.flag(), deepPass.flag(), "the deep pass sees the same flag the heartbeat sets");
                request.flag().set(true);
                assertTrue(RequestCancellation.isCancelled());
            }
        }
        assertNull(RequestCancellation.getFlag(), "the owner removed it");
    }

    @Test
    void aNewScopeAfterTheOwnerClosesGetsAFreshFlag() {
        var first = RequestCancellation.acquire();
        var firstFlag = first.flag();
        first.close();
        try (var second = RequestCancellation.acquire()) {
            assertTrue(second.isOwner());
            assertNotNull(second.flag());
            assertNotSame(firstFlag, second.flag());
        }
        assertNull(RequestCancellation.getFlag());
    }
}
