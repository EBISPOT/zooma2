package uk.ac.ebi.zooma2.api.v2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.javalin.http.BadRequestResponse;
import uk.ac.ebi.zooma2.api.RequestLimits;
import uk.ac.ebi.zooma2.api.v2.dto.V2StringToMapDto;

/** The legacy job must not be evicted while running, must not report done before it is, and must be bounded (issue #17). */
class V2JobPolicyTest {

    private static V2StringToMapDto row(String value, String type) {
        var r = new V2StringToMapDto();
        r.propertyValue = value;
        r.propertyType = type;
        return r;
    }

    @Test
    void runningJobsAreNotEvictedByAge() {
        long start = 1_000_000L;
        var job = new ZoomaApiV2.MapJob(List.of(row("rat", "organism")), null, start);
        assertFalse(ZoomaApiV2.shouldEvict(job, start + 45 * 60 * 1000L), "45 minutes in, still running: keep it");
        assertTrue(ZoomaApiV2.shouldEvict(job, start + ZoomaApiV2.EVICT_RUNNING_AFTER_MS + 1), "a stuck job is bounded");
    }

    @Test
    void completedJobsAreEvictedByCompletionAge() {
        long start = 1_000_000L;
        var job = new ZoomaApiV2.MapJob(List.of(row("rat", "organism")), null, start);
        job.complete(List.of());
        long done = job.completedAt;
        assertFalse(ZoomaApiV2.shouldEvict(job, done + 10 * 60 * 1000L));
        assertTrue(ZoomaApiV2.shouldEvict(job, done + ZoomaApiV2.EVICT_COMPLETED_AFTER_MS + 1));
    }

    @Test
    void progressReachesOneOnlyWhenResultsArePublished() {
        var job = new ZoomaApiV2.MapJob(List.of(row("rat", "organism")), null);
        assertTrue(job.results == null && job.progress < 1.0);
        job.complete(List.of());
        assertTrue(job.results != null && job.progress == 1.0 && job.completedAt > 0);
    }

    @Test
    void submissionsAreBoundedLikeV3() {
        assertThrows(BadRequestResponse.class, () -> ZoomaApiV2.validateSubmission(new V2StringToMapDto[0]));
        assertThrows(BadRequestResponse.class, () -> ZoomaApiV2.validateSubmission(new V2StringToMapDto[] {row("", null)}));
        assertThrows(BadRequestResponse.class, () -> ZoomaApiV2.validateSubmission(new V2StringToMapDto[] {row("x".repeat(RequestLimits.MAX_PROPERTY_TEXT_LENGTH + 1), null)}));
        assertThrows(BadRequestResponse.class, () -> ZoomaApiV2.validateSubmission(new V2StringToMapDto[] {row("rat", "t".repeat(RequestLimits.MAX_PROPERTY_TYPE_LENGTH + 1))}));
        assertThrows(BadRequestResponse.class, () -> ZoomaApiV2.validateSubmission(new V2StringToMapDto[] {null}));
        var tooMany = new V2StringToMapDto[RequestLimits.MAX_PROPERTIES + 1];
        java.util.Arrays.fill(tooMany, row("rat", null));
        assertThrows(BadRequestResponse.class, () -> ZoomaApiV2.validateSubmission(tooMany));
        ZoomaApiV2.validateSubmission(new V2StringToMapDto[] {row("rat", "organism"), row("mice", null)});
    }
}
