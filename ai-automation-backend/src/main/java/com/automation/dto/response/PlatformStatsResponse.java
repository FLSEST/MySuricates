package com.automation.dto.response;

import java.util.List;

public record PlatformStatsResponse(
        long activeProjects,
        long archivedProjects,
        long totalWorkflows,
        long workflowPoolSize,
        long totalSkills,
        long builtinSkills,
        long customSkills,
        List<SkillUsageEntry> topSkillsByUsage,
        long totalTemplates,
        long builtinTemplates,
        long customTemplates,
        long activeSchedules,
        long totalSchedules,
        long activeWebhooks,
        long totalWebhooks,
        ChainStats chainStats,
        long activeTodoTriggers,
        long activeCompletionTriggers,
        long activeMemoryEntries,
        TodoStats todoStats,
        WishStats wishStats,
        AssistantStats assistantStats
) {
    public record SkillUsageEntry(String skillId, String skillName, String category, long workflowCount) {}
    public record ChainStats(long totalChains, long totalRuns, long successfulRuns, long failedRuns) {}
    public record TodoStats(long open, long inProgress, long done, long backlog,
                            long fromUser, long fromAi, long fromWorkflow) {}
    public record WishStats(long total, long completed, long failed, long inProgress) {}
    public record AssistantStats(long totalSessions, double avgMessagesPerSession) {}
}
