package com.automation.dto.bundle;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A whole project as one portable JSON file ({@code <slug>.mysuricates-bundle.json}): master data,
 * workflows, chains and schedules — without any UUID of this instance. Workflows and chains are
 * named by a {@code key} unique within the bundle; sub-workflow references are
 * {@code @@workflow:<key>@@}, skills are named, absolute paths are {@code @@path:N@@} tokens listed
 * in {@link #paths}. Format described in docs/architektur.md → "Projekt-Bündel".
 */
public record ProjectBundle(
        String format,
        Integer formatVersion,
        Instant exportedAt,
        String appVersion,
        BundleProject project,
        List<BundleWorkflow> workflows,
        List<BundleChain> chains,
        List<BundleSchedule> schedules,
        List<BundlePath> paths,
        List<String> skillNames,
        List<String> warnings
) {
    public static final String FORMAT = "mysuricates-project-bundle";
    public static final int FORMAT_VERSION = 1;

    public ProjectBundle {
        workflows = workflows == null ? List.of() : workflows;
        chains = chains == null ? List.of() : chains;
        schedules = schedules == null ? List.of() : schedules;
        paths = paths == null ? List.of() : paths;
        skillNames = skillNames == null ? List.of() : skillNames;
        warnings = warnings == null ? List.of() : warnings;
    }

    /** Project master data — deliberately without id, path, container and status. */
    public record BundleProject(String name, String subtitle, String description, String type,
                                String aiContext, Map<String, Object> config) {}

    public record BundleWorkflow(String key, BundleDefinition definition) {}

    /**
     * The executable definition as the single-workflow export carries it, without id, project,
     * seal fields and hash. {@code blocks} stay raw maps so nothing of a block is lost in transit.
     */
    public record BundleDefinition(String name, String description, String icon,
                                   List<Map<String, Object>> blocks, String instructions, String model,
                                   String provider, String effort, String version,
                                   Map<String, Object> defaultContext, Map<String, Object> metadata,
                                   List<String> activatedSkillNames) {}

    public record BundleChain(String key, String name, String description, String icon,
                              Boolean stopOnFailure, List<BundleChainEntry> entries) {}

    public record BundleChainEntry(String workflowKey, String label, int sortOrder, Boolean stopOnFailure,
                                   Map<String, Object> inputContext, Integer timeoutMinutes) {}

    /** No {@code enabled}: an imported schedule always starts disabled. */
    public record BundleSchedule(BundleTarget target, String cronExpression, String scheduleType,
                                 Instant scheduledAt, String delayExpression, String description,
                                 Map<String, Object> inputContext, String conflictBehavior,
                                 Boolean catchUpOnResume, Integer maxRuns, Instant endDate) {}

    /** Exactly one of both is set. */
    public record BundleTarget(String workflowKey, String chainKey) {

        /** {@code workflow:<key>} or {@code chain:<key>} — the preview's label. */
        public String label() {
            return workflowKey != null ? "workflow:" + workflowKey : "chain:" + chainKey;
        }
    }

    /**
     * One absolute path of the source instance: {@code token} stands for {@code original} in
     * {@code occurrences} places (sub paths included); {@code hint} names the first one.
     */
    public record BundlePath(String token, String original, int occurrences, String hint) {}
}
