package uk.ac.ebi.zooma2.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The request caps answer to their new names and still to the names they were introduced under (issue #48). */
class RequestLimitsEnvAliasTest {

    @Test
    void newNameWinsLegacyNameStillHonoured() {
        assertEquals(50, RequestLimits.envInt(Map.of("ZOOMA2_MAX_STRINGS", "50", "ZOOMA2_MAX_PROPERTIES", "70")::get,
            "ZOOMA2_MAX_STRINGS", "ZOOMA2_MAX_PROPERTIES", 1000, 1, 100_000));
        assertEquals(70, RequestLimits.envInt(Map.of("ZOOMA2_MAX_PROPERTIES", "70")::get,
            "ZOOMA2_MAX_STRINGS", "ZOOMA2_MAX_PROPERTIES", 1000, 1, 100_000));
        assertEquals(1000, RequestLimits.envInt(Map.<String, String>of()::get,
            "ZOOMA2_MAX_STRINGS", "ZOOMA2_MAX_PROPERTIES", 1000, 1, 100_000));
    }

    @Test
    void invalidValuesFallBackToTheDefaultUnderEitherName() {
        assertEquals(1000, RequestLimits.envInt(Map.of("ZOOMA2_MAX_PROPERTIES", "lots")::get,
            "ZOOMA2_MAX_STRINGS", "ZOOMA2_MAX_PROPERTIES", 1000, 1, 100_000));
        assertEquals(1000, RequestLimits.envInt(Map.of("ZOOMA2_MAX_STRINGS", "0")::get,
            "ZOOMA2_MAX_STRINGS", null, 1000, 1, 100_000));
        // an explicitly set new name is authoritative even when invalid: the legacy value is not consulted
        assertEquals(1000, RequestLimits.envInt(Map.of("ZOOMA2_MAX_STRINGS", "0", "ZOOMA2_MAX_PROPERTIES", "70")::get,
            "ZOOMA2_MAX_STRINGS", "ZOOMA2_MAX_PROPERTIES", 1000, 1, 100_000));
    }
}
