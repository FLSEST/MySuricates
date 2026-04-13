package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformStatsResponseTest {

    // --- Helper: build a full response with sensible defaults ---

    private PlatformStatsResponse.SkillUsageEntry sampleSkillUsage() {
        return new PlatformStatsResponse.SkillUsageEntry("skill-1", "Shell Executor", "execution", 42);
    }

    private PlatformStatsResponse.ChainStats sampleChainStats() {
        return new PlatformStatsResponse.ChainStats(10, 50, 45, 5);
    }

    private PlatformStatsResponse.TodoStats sampleTodoStats() {
        return new PlatformStatsResponse.TodoStats(3, 2, 10, 1, 8, 5, 3);
    }

    private PlatformStatsResponse.WishStats sampleWishStats() {
        return new PlatformStatsResponse.WishStats(20, 15, 2, 3);
    }

    private PlatformStatsResponse.AssistantStats sampleAssistantStats() {
        return new PlatformStatsResponse.AssistantStats(100, 7.5);
    }

    private PlatformStatsResponse fullResponse() {
        return new PlatformStatsResponse(
                5, 2, 30, 10, 15, 10, 5,
                List.of(sampleSkillUsage()),
                8, 6, 2,
                3, 7,
                2, 4,
                sampleChainStats(),
                1, 2, 50,
                sampleTodoStats(),
                sampleWishStats(),
                sampleAssistantStats()
        );
    }

    // =====================================================================
    // Main record — Constructor & accessors
    // =====================================================================

    @Test
    void should_returnAllFields_when_constructedWithFullData() {
        var response = fullResponse();

        assertThat(response.activeProjects()).isEqualTo(5);
        assertThat(response.archivedProjects()).isEqualTo(2);
        assertThat(response.totalWorkflows()).isEqualTo(30);
        assertThat(response.workflowPoolSize()).isEqualTo(10);
        assertThat(response.totalSkills()).isEqualTo(15);
        assertThat(response.builtinSkills()).isEqualTo(10);
        assertThat(response.customSkills()).isEqualTo(5);
        assertThat(response.topSkillsByUsage()).hasSize(1);
        assertThat(response.totalTemplates()).isEqualTo(8);
        assertThat(response.builtinTemplates()).isEqualTo(6);
        assertThat(response.customTemplates()).isEqualTo(2);
        assertThat(response.activeSchedules()).isEqualTo(3);
        assertThat(response.totalSchedules()).isEqualTo(7);
        assertThat(response.activeWebhooks()).isEqualTo(2);
        assertThat(response.totalWebhooks()).isEqualTo(4);
        assertThat(response.chainStats()).isNotNull();
        assertThat(response.activeTodoTriggers()).isEqualTo(1);
        assertThat(response.activeCompletionTriggers()).isEqualTo(2);
        assertThat(response.activeMemoryEntries()).isEqualTo(50);
        assertThat(response.todoStats()).isNotNull();
        assertThat(response.wishStats()).isNotNull();
        assertThat(response.assistantStats()).isNotNull();
    }

    @Test
    void should_returnZeros_when_constructedWithAllZeros() {
        var response = new PlatformStatsResponse(
                0, 0, 0, 0, 0, 0, 0,
                List.of(),
                0, 0, 0,
                0, 0,
                0, 0,
                new PlatformStatsResponse.ChainStats(0, 0, 0, 0),
                0, 0, 0,
                new PlatformStatsResponse.TodoStats(0, 0, 0, 0, 0, 0, 0),
                new PlatformStatsResponse.WishStats(0, 0, 0, 0),
                new PlatformStatsResponse.AssistantStats(0, 0.0)
        );

        assertThat(response.activeProjects()).isZero();
        assertThat(response.totalWorkflows()).isZero();
        assertThat(response.topSkillsByUsage()).isEmpty();
    }

    @Test
    void should_acceptNullList_when_topSkillsByUsageIsNull() {
        var response = new PlatformStatsResponse(
                1, 0, 5, 2, 3, 2, 1,
                null,
                1, 1, 0,
                0, 0,
                0, 0,
                sampleChainStats(),
                0, 0, 0,
                sampleTodoStats(),
                sampleWishStats(),
                sampleAssistantStats()
        );

        assertThat(response.topSkillsByUsage()).isNull();
    }

    @Test
    void should_acceptNullNestedRecords_when_statsAreNull() {
        var response = new PlatformStatsResponse(
                1, 0, 5, 2, 3, 2, 1,
                List.of(),
                1, 1, 0,
                0, 0,
                0, 0,
                null,
                0, 0, 0,
                null,
                null,
                null
        );

        assertThat(response.chainStats()).isNull();
        assertThat(response.todoStats()).isNull();
        assertThat(response.wishStats()).isNull();
        assertThat(response.assistantStats()).isNull();
    }

    @Test
    void should_returnMultipleSkills_when_topSkillsByUsageHasMultipleEntries() {
        var skill1 = new PlatformStatsResponse.SkillUsageEntry("s1", "Skill A", "cat-a", 100);
        var skill2 = new PlatformStatsResponse.SkillUsageEntry("s2", "Skill B", "cat-b", 50);
        var skill3 = new PlatformStatsResponse.SkillUsageEntry("s3", "Skill C", "cat-a", 25);

        var response = new PlatformStatsResponse(
                1, 0, 5, 2, 3, 2, 1,
                List.of(skill1, skill2, skill3),
                1, 1, 0,
                0, 0,
                0, 0,
                sampleChainStats(),
                0, 0, 0,
                sampleTodoStats(),
                sampleWishStats(),
                sampleAssistantStats()
        );

        assertThat(response.topSkillsByUsage()).hasSize(3).containsExactly(skill1, skill2, skill3);
    }

    // =====================================================================
    // SkillUsageEntry
    // =====================================================================

    @Test
    void should_returnAllSkillUsageFields_when_constructed() {
        var entry = new PlatformStatsResponse.SkillUsageEntry("skill-42", "AI Writer", "content", 99);

        assertThat(entry.skillId()).isEqualTo("skill-42");
        assertThat(entry.skillName()).isEqualTo("AI Writer");
        assertThat(entry.category()).isEqualTo("content");
        assertThat(entry.workflowCount()).isEqualTo(99);
    }

    @Test
    void should_acceptNullStrings_when_skillUsageConstructedWithNulls() {
        var entry = new PlatformStatsResponse.SkillUsageEntry(null, null, null, 0);

        assertThat(entry.skillId()).isNull();
        assertThat(entry.skillName()).isNull();
        assertThat(entry.category()).isNull();
        assertThat(entry.workflowCount()).isZero();
    }

    @Test
    void should_beEqual_when_skillUsageEntriesHaveSameValues() {
        var a = new PlatformStatsResponse.SkillUsageEntry("s1", "Shell", "exec", 10);
        var b = new PlatformStatsResponse.SkillUsageEntry("s1", "Shell", "exec", 10);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_skillUsageEntriesDiffer() {
        var a = new PlatformStatsResponse.SkillUsageEntry("s1", "Shell", "exec", 10);
        var b = new PlatformStatsResponse.SkillUsageEntry("s2", "Shell", "exec", 10);

        assertThat(a).isNotEqualTo(b);
    }

    // =====================================================================
    // ChainStats
    // =====================================================================

    @Test
    void should_returnAllChainStatsFields_when_constructed() {
        var stats = new PlatformStatsResponse.ChainStats(10, 100, 90, 10);

        assertThat(stats.totalChains()).isEqualTo(10);
        assertThat(stats.totalRuns()).isEqualTo(100);
        assertThat(stats.successfulRuns()).isEqualTo(90);
        assertThat(stats.failedRuns()).isEqualTo(10);
    }

    @Test
    void should_returnZeros_when_chainStatsAllZero() {
        var stats = new PlatformStatsResponse.ChainStats(0, 0, 0, 0);

        assertThat(stats.totalChains()).isZero();
        assertThat(stats.totalRuns()).isZero();
        assertThat(stats.successfulRuns()).isZero();
        assertThat(stats.failedRuns()).isZero();
    }

    @Test
    void should_beEqual_when_chainStatsHaveSameValues() {
        var a = new PlatformStatsResponse.ChainStats(5, 20, 18, 2);
        var b = new PlatformStatsResponse.ChainStats(5, 20, 18, 2);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_chainStatsDiffer() {
        var a = new PlatformStatsResponse.ChainStats(5, 20, 18, 2);
        var b = new PlatformStatsResponse.ChainStats(5, 20, 17, 3);

        assertThat(a).isNotEqualTo(b);
    }

    // =====================================================================
    // TodoStats
    // =====================================================================

    @Test
    void should_returnAllTodoStatsFields_when_constructed() {
        var stats = new PlatformStatsResponse.TodoStats(5, 3, 20, 2, 15, 8, 7);

        assertThat(stats.open()).isEqualTo(5);
        assertThat(stats.inProgress()).isEqualTo(3);
        assertThat(stats.done()).isEqualTo(20);
        assertThat(stats.backlog()).isEqualTo(2);
        assertThat(stats.fromUser()).isEqualTo(15);
        assertThat(stats.fromAi()).isEqualTo(8);
        assertThat(stats.fromWorkflow()).isEqualTo(7);
    }

    @Test
    void should_returnZeros_when_todoStatsAllZero() {
        var stats = new PlatformStatsResponse.TodoStats(0, 0, 0, 0, 0, 0, 0);

        assertThat(stats.open()).isZero();
        assertThat(stats.inProgress()).isZero();
        assertThat(stats.done()).isZero();
        assertThat(stats.backlog()).isZero();
        assertThat(stats.fromUser()).isZero();
        assertThat(stats.fromAi()).isZero();
        assertThat(stats.fromWorkflow()).isZero();
    }

    @Test
    void should_beEqual_when_todoStatsHaveSameValues() {
        var a = new PlatformStatsResponse.TodoStats(1, 2, 3, 4, 5, 6, 7);
        var b = new PlatformStatsResponse.TodoStats(1, 2, 3, 4, 5, 6, 7);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_todoStatsDiffer() {
        var a = new PlatformStatsResponse.TodoStats(1, 2, 3, 4, 5, 6, 7);
        var b = new PlatformStatsResponse.TodoStats(1, 2, 3, 4, 5, 6, 8);

        assertThat(a).isNotEqualTo(b);
    }

    // =====================================================================
    // WishStats
    // =====================================================================

    @Test
    void should_returnAllWishStatsFields_when_constructed() {
        var stats = new PlatformStatsResponse.WishStats(50, 40, 5, 5);

        assertThat(stats.total()).isEqualTo(50);
        assertThat(stats.completed()).isEqualTo(40);
        assertThat(stats.failed()).isEqualTo(5);
        assertThat(stats.inProgress()).isEqualTo(5);
    }

    @Test
    void should_returnZeros_when_wishStatsAllZero() {
        var stats = new PlatformStatsResponse.WishStats(0, 0, 0, 0);

        assertThat(stats.total()).isZero();
        assertThat(stats.completed()).isZero();
        assertThat(stats.failed()).isZero();
        assertThat(stats.inProgress()).isZero();
    }

    @Test
    void should_beEqual_when_wishStatsHaveSameValues() {
        var a = new PlatformStatsResponse.WishStats(10, 8, 1, 1);
        var b = new PlatformStatsResponse.WishStats(10, 8, 1, 1);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_wishStatsDiffer() {
        var a = new PlatformStatsResponse.WishStats(10, 8, 1, 1);
        var b = new PlatformStatsResponse.WishStats(10, 7, 2, 1);

        assertThat(a).isNotEqualTo(b);
    }

    // =====================================================================
    // AssistantStats
    // =====================================================================

    @Test
    void should_returnAllAssistantStatsFields_when_constructed() {
        var stats = new PlatformStatsResponse.AssistantStats(200, 12.3);

        assertThat(stats.totalSessions()).isEqualTo(200);
        assertThat(stats.avgMessagesPerSession()).isEqualTo(12.3);
    }

    @Test
    void should_returnZeros_when_assistantStatsAllZero() {
        var stats = new PlatformStatsResponse.AssistantStats(0, 0.0);

        assertThat(stats.totalSessions()).isZero();
        assertThat(stats.avgMessagesPerSession()).isZero();
    }

    @Test
    void should_handleLargeAvg_when_assistantStatsHasHighAverage() {
        var stats = new PlatformStatsResponse.AssistantStats(1, 999.999);

        assertThat(stats.avgMessagesPerSession()).isEqualTo(999.999);
    }

    @Test
    void should_beEqual_when_assistantStatsHaveSameValues() {
        var a = new PlatformStatsResponse.AssistantStats(50, 5.5);
        var b = new PlatformStatsResponse.AssistantStats(50, 5.5);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_assistantStatsDiffer() {
        var a = new PlatformStatsResponse.AssistantStats(50, 5.5);
        var b = new PlatformStatsResponse.AssistantStats(50, 6.0);

        assertThat(a).isNotEqualTo(b);
    }

    // =====================================================================
    // Main record — Equality
    // =====================================================================

    @Test
    void should_beEqual_when_mainRecordsHaveSameValues() {
        var a = fullResponse();
        var b = fullResponse();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_activeProjectsDiffer() {
        var a = fullResponse();
        var b = new PlatformStatsResponse(
                99, 2, 30, 10, 15, 10, 5,
                List.of(sampleSkillUsage()),
                8, 6, 2,
                3, 7,
                2, 4,
                sampleChainStats(),
                1, 2, 50,
                sampleTodoStats(),
                sampleWishStats(),
                sampleAssistantStats()
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        assertThat(fullResponse()).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        assertThat(fullResponse()).isNotEqualTo("not a PlatformStatsResponse");
    }

    // =====================================================================
    // toString
    // =====================================================================

    @Test
    void should_containFieldNames_when_toStringCalled() {
        var str = fullResponse().toString();

        assertThat(str).contains("activeProjects");
        assertThat(str).contains("totalWorkflows");
        assertThat(str).contains("chainStats");
        assertThat(str).contains("todoStats");
        assertThat(str).contains("wishStats");
        assertThat(str).contains("assistantStats");
    }

    @Test
    void should_containNestedRecordValues_when_toStringCalled() {
        var str = fullResponse().toString();

        assertThat(str).contains("Shell Executor");
        assertThat(str).contains("skill-1");
    }
}
