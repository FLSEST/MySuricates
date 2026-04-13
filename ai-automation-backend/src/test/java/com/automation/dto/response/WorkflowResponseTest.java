package com.automation.dto.response;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowResponseTest {

    private static WorkflowResponse minimal() {
        return new WorkflowResponse(
                "wf-1", "proj-1", "Project", null, null,
                "My Workflow", null, null,
                List.of(), "1.0", Map.of(),
                List.of(), List.of(), 0L,
                null, null, null, null, Map.of(),
                null, null, Instant.EPOCH, Instant.EPOCH, null
        );
    }

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var block = mock(BlockDefinition.class);
        var now = Instant.parse("2024-06-01T10:00:00Z");
        var meta = Map.<String, Object>of("key", "value");
        var defaultCtx = Map.<String, Object>of("ctx", "val");

        var response = new WorkflowResponse(
                "wf-1", "proj-1", "My Project", "Subtitle", "Description",
                "My Workflow", "Does things", "icon.png",
                List.of(block), "2.0", meta,
                List.of("skill-1"), List.of("SkillOne"), 42L,
                "instructions here", "claude-sonnet-4-6", "ANTHROPIC", "tmpl-1", defaultCtx,
                "PASSED", now, now, now, now
        );

        assertThat(response.id()).isEqualTo("wf-1");
        assertThat(response.projectId()).isEqualTo("proj-1");
        assertThat(response.projectName()).isEqualTo("My Project");
        assertThat(response.projectSubtitle()).isEqualTo("Subtitle");
        assertThat(response.projectDescription()).isEqualTo("Description");
        assertThat(response.name()).isEqualTo("My Workflow");
        assertThat(response.description()).isEqualTo("Does things");
        assertThat(response.icon()).isEqualTo("icon.png");
        assertThat(response.blocks()).containsExactly(block);
        assertThat(response.version()).isEqualTo("2.0");
        assertThat(response.metadata()).isEqualTo(meta);
        assertThat(response.activatedSkillIds()).containsExactly("skill-1");
        assertThat(response.activatedSkillNames()).containsExactly("SkillOne");
        assertThat(response.runCount()).isEqualTo(42L);
        assertThat(response.instructions()).isEqualTo("instructions here");
        assertThat(response.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(response.provider()).isEqualTo("ANTHROPIC");
        assertThat(response.templateId()).isEqualTo("tmpl-1");
        assertThat(response.defaultContext()).isEqualTo(defaultCtx);
        assertThat(response.testStatus()).isEqualTo("PASSED");
        assertThat(response.lastTestedAt()).isEqualTo(now);
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.updatedAt()).isEqualTo(now);
        assertThat(response.stableSyncedAt()).isEqualTo(now);
    }

    @Test
    void should_returnZeroRunCount_when_constructedWithZero() {
        var response = minimal();

        assertThat(response.runCount()).isZero();
    }

    @Test
    void should_returnEmptyBlocks_when_constructedWithEmptyList() {
        var response = minimal();

        assertThat(response.blocks()).isEmpty();
    }

    @Test
    void should_returnMultipleBlocks_when_constructedWithMultipleBlocks() {
        var block1 = mock(BlockDefinition.class);
        var block2 = mock(BlockDefinition.class);
        var response = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(block1, block2), "1.0", Map.of(),
                List.of(), List.of(), 0L,
                null, null, null, null, Map.of(),
                null, null, Instant.EPOCH, Instant.EPOCH, null
        );

        assertThat(response.blocks()).containsExactly(block1, block2);
    }

    @Test
    void should_acceptNullOptionalFields_when_constructed() {
        var response = new WorkflowResponse(
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                null, null, 0L,
                null, null, null, null, null,
                null, null, null, null, null
        );

        assertThat(response.id()).isNull();
        assertThat(response.projectId()).isNull();
        assertThat(response.projectName()).isNull();
        assertThat(response.projectSubtitle()).isNull();
        assertThat(response.projectDescription()).isNull();
        assertThat(response.name()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.icon()).isNull();
        assertThat(response.blocks()).isNull();
        assertThat(response.version()).isNull();
        assertThat(response.metadata()).isNull();
        assertThat(response.activatedSkillIds()).isNull();
        assertThat(response.activatedSkillNames()).isNull();
        assertThat(response.instructions()).isNull();
        assertThat(response.model()).isNull();
        assertThat(response.provider()).isNull();
        assertThat(response.templateId()).isNull();
        assertThat(response.defaultContext()).isNull();
        assertThat(response.testStatus()).isNull();
        assertThat(response.lastTestedAt()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.updatedAt()).isNull();
        assertThat(response.stableSyncedAt()).isNull();
    }

    @Test
    void should_preserveInstants_when_differentTimestamps() {
        var created = Instant.parse("2024-01-01T00:00:00Z");
        var updated = Instant.parse("2024-06-15T12:30:00Z");
        var tested = Instant.parse("2024-06-14T08:00:00Z");
        var synced = Instant.parse("2024-06-10T00:00:00Z");

        var response = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(), "1.0", Map.of(),
                List.of(), List.of(), 5L,
                null, null, null, null, Map.of(),
                "PASSED", tested, created, updated, synced
        );

        assertThat(response.createdAt()).isEqualTo(created);
        assertThat(response.updatedAt()).isEqualTo(updated);
        assertThat(response.lastTestedAt()).isEqualTo(tested);
        assertThat(response.stableSyncedAt()).isEqualTo(synced);
    }

    @Test
    void should_preserveMetadata_when_constructedWithMap() {
        var meta = Map.<String, Object>of("foo", "bar", "count", 42);
        var response = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(), "1.0", meta,
                List.of(), List.of(), 0L,
                null, null, null, null, Map.of(),
                null, null, Instant.EPOCH, Instant.EPOCH, null
        );

        assertThat(response.metadata()).containsEntry("foo", "bar");
        assertThat(response.metadata()).containsEntry("count", 42);
    }

    @Test
    void should_preserveSkillIdsAndNames_when_constructedWithMultiple() {
        var response = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(), "1.0", Map.of(),
                List.of("s1", "s2", "s3"), List.of("SkillA", "SkillB", "SkillC"), 0L,
                null, null, null, null, Map.of(),
                null, null, Instant.EPOCH, Instant.EPOCH, null
        );

        assertThat(response.activatedSkillIds()).containsExactly("s1", "s2", "s3");
        assertThat(response.activatedSkillNames()).containsExactly("SkillA", "SkillB", "SkillC");
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var now = Instant.EPOCH;
        var a = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(), "1.0", Map.of(),
                List.of(), List.of(), 0L,
                null, null, null, null, Map.of(),
                null, null, now, now, null
        );
        var b = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(), "1.0", Map.of(),
                List.of(), List.of(), 0L,
                null, null, null, null, Map.of(),
                null, null, now, now, null
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothAllNull() {
        var a = new WorkflowResponse(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, 0L,
                null, null, null, null, null, null, null, null, null, null
        );
        var b = new WorkflowResponse(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, 0L,
                null, null, null, null, null, null, null, null, null, null
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = minimal();
        var b = new WorkflowResponse(
                "wf-2", "proj-1", "Project", null, null,
                "My Workflow", null, null,
                List.of(), "1.0", Map.of(),
                List.of(), List.of(), 0L,
                null, null, null, null, Map.of(),
                null, null, Instant.EPOCH, Instant.EPOCH, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentName() {
        var now = Instant.EPOCH;
        var a = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "Workflow A", null, null,
                List.of(), "1.0", Map.of(), List.of(), List.of(), 0L,
                null, null, null, null, Map.of(), null, null, now, now, null
        );
        var b = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "Workflow B", null, null,
                List.of(), "1.0", Map.of(), List.of(), List.of(), 0L,
                null, null, null, null, Map.of(), null, null, now, now, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentRunCount() {
        var now = Instant.EPOCH;
        var a = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(), "1.0", Map.of(), List.of(), List.of(), 0L,
                null, null, null, null, Map.of(), null, null, now, now, null
        );
        var b = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(), "1.0", Map.of(), List.of(), List.of(), 1L,
                null, null, null, null, Map.of(), null, null, now, now, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentCreatedAt() {
        var a = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(), "1.0", Map.of(), List.of(), List.of(), 0L,
                null, null, null, null, Map.of(), null, null,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.EPOCH, null
        );
        var b = new WorkflowResponse(
                "wf-1", "proj-1", "P", null, null, "W", null, null,
                List.of(), "1.0", Map.of(), List.of(), List.of(), 0L,
                null, null, null, null, Map.of(), null, null,
                Instant.parse("2024-06-01T00:00:00Z"), Instant.EPOCH, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        assertThat(minimal()).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldNames_when_toStringCalled() {
        var response = minimal();
        var str = response.toString();

        assertThat(str).contains("id");
        assertThat(str).contains("name");
        assertThat(str).contains("projectId");
        assertThat(str).contains("runCount");
    }

    @Test
    void should_containFieldValues_when_toStringCalledWithPopulatedResponse() {
        var now = Instant.EPOCH;
        var response = new WorkflowResponse(
                "wf-42", "proj-99", "Test Project", null, null, "My WF", null, null,
                List.of(), "3.0", Map.of(), List.of(), List.of(), 7L,
                null, "claude-sonnet-4-6", null, null, Map.of(),
                "FAILED", null, now, now, null
        );
        var str = response.toString();

        assertThat(str).contains("wf-42");
        assertThat(str).contains("proj-99");
        assertThat(str).contains("Test Project");
        assertThat(str).contains("My WF");
        assertThat(str).contains("3.0");
        assertThat(str).contains("7");
        assertThat(str).contains("claude-sonnet-4-6");
        assertThat(str).contains("FAILED");
    }

    @Test
    void should_containNull_when_toStringCalledWithNullFields() {
        var response = new WorkflowResponse(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, 0L,
                null, null, null, null, null, null, null, null, null, null
        );
        var str = response.toString();

        assertThat(str).contains("null");
    }
}
