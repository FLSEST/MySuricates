package com.automation.dto.bundle;

import java.util.List;

/** What an import would create — shown before the user confirms; nothing is written. */
public record BundlePreview(ProjectInfo project, List<WorkflowInfo> workflows, List<ChainInfo> chains,
                            List<ScheduleInfo> schedules, List<ProjectBundle.BundlePath> paths,
                            List<String> warnings) {

    public record ProjectInfo(String name, boolean nameTaken) {}

    /** {@code shellCommands}: the first 200 characters of every shell field, nested blocks included. */
    public record WorkflowInfo(String key, String name, int blockCount, List<String> blockTypes,
                               List<String> providers, List<String> models, List<String> shellCommands,
                               boolean nameTakenInTarget) {}

    public record ChainInfo(String key, String name, int entries) {}

    public record ScheduleInfo(String target, String cronExpression, boolean willBeDisabled) {}
}
