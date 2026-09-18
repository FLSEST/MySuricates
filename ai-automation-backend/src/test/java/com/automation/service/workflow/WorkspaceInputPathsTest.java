package com.automation.service.workflow;

import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceInputPathsTest {
    @Test
    void mapsFileInputsButPreservesProjectIdentityAndUntrustedClassification() {
        var context = ExecutionContext.builder().workflowRunId("run").build();
        context.setVariable("project_path", "/source/repo");
        context.setVariable("target_file", "/source/repo/src/Example.java");
        context.setVariable("references", List.of("/source/repo/test/ExampleTest.java", "/source/repository/other"));
        context.setUntrustedVariable("todo_description", "Fix '/source/repo/src/Example.java'; $(echo untrusted)");
        WorkspaceInputPaths.remap(context, Map.of("sourceRepository", "/source/repo", "workspacePath", "/runs/run/repo"));
        assertThat(context.getVariable("project_path")).isEqualTo("/source/repo");
        assertThat(context.getVariable("target_file")).isEqualTo("/runs/run/repo/src/Example.java");
        assertThat(context.getVariable("references")).isEqualTo(List.of("/runs/run/repo/test/ExampleTest.java", "/source/repository/other"));
        assertThat(context.getVariable("todo_description")).isEqualTo("Fix '/runs/run/repo/src/Example.java'; $(echo untrusted)");
        assertThat(context.isUntrusted("todo_description")).isTrue();
    }

    @Test
    void keepsPlatformDataUntrustedWithoutTaintingTheRun() {
        var context = ExecutionContext.builder().workflowRunId("run").build();
        context.setUntrustedPlatformVariable("previous_report", "siehe /source/repo/src/Example.java");

        WorkspaceInputPaths.remap(context, Map.of("sourceRepository", "/source/repo", "workspacePath", "/runs/run/repo"));

        assertThat(context.getVariable("previous_report")).isEqualTo("siehe /runs/run/repo/src/Example.java");
        assertThat(context.isUntrusted("previous_report")).isTrue();
        // Platform data must not be upgraded to external input — that would taint every block output
        assertThat(context.hasUntrustedInput()).isFalse();
    }
}
