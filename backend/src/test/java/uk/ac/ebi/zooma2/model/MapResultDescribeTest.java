package uk.ac.ebi.zooma2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MapResultDescribeTest {

    @Test
    void messagelessExceptionsKeepTheirClassName() {
        assertEquals("NullPointerException", MapResult.describe(new NullPointerException()));
        assertEquals("IllegalStateException", MapResult.describe(new IllegalStateException("")));
    }

    @Test
    void messagesArePrefixedWithTheClassName() {
        assertEquals("RuntimeException: Search failed", MapResult.describe(new RuntimeException("Search failed")));
        assertEquals("unknown error", MapResult.describe(null));
    }
}
