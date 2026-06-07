package com.automation.mapper;

import com.automation.dto.response.StepResultResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the protected helper methods in WorkflowMapper.
 * The abstract MapStruct-generated mapping is tested indirectly via controller integration tests.
 */
class WorkflowMapperTest extends WorkflowMapper {

    // Implement abstract methods to make this concrete (MapStruct generates these normally)
    @Override public WorkflowResponse toResponse(Workflow workflow) { return null; }
    @Override public List<WorkflowResponse> toResponseList(List<Workflow> workflows) { return null; }
    @Override public WorkflowRunResponse toRunResponse(WorkflowRun run) { return null; }
    @Override public List<WorkflowRunResponse> toRunResponseList(List<WorkflowRun> runs) { return null; }
    @Override public StepResultResponse toStepResponse(StepResult step) { return null; }
    @Override public WorkflowResponse toResponse(Workflow workflow, Map<String, Long> runCounts) { return null; }
    @Override public List<WorkflowResponse> toResponseList(List<Workflow> workflows, Map<String, Long> runCounts) { return null; }

    @Nested
    class SafeWorkflowId {

        @Test
        void withWorkflow_returnsId() {
            Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).build();

            assertThat(safeWorkflowId(run)).isEqualTo("wf-1");
        }

        @Test
        void nullWorkflow_returnsNull() {
            WorkflowRun run = WorkflowRun.builder().id("r-1").build();

            assertThat(safeWorkflowId(run)).isNull();
        }
    }

    @Nested
    class SafeWorkflowName {

        @Test
        void withWorkflow_returnsName() {
            Workflow wf = Workflow.builder().id("wf-1").name("My Workflow").build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).build();

            assertThat(safeWorkflowName(run)).isEqualTo("My Workflow");
        }

        @Test
        void nullWorkflow_returnsDeleted() {
            WorkflowRun run = WorkflowRun.builder().id("r-1").build();

            assertThat(safeWorkflowName(run)).isEqualTo("(deleted)");
        }

        @Test
        void snapshotName_preferredOverLiveWorkflow() {
            Workflow wf = Workflow.builder().id("wf-1").name("Renamed").build();
            WorkflowRun run = WorkflowRun.builder()
                    .id("r-1").workflow(wf).workflowName("OriginalName").build();

            assertThat(safeWorkflowName(run)).isEqualTo("OriginalName");
        }

        @Test
        void snapshotName_usedWhenWorkflowSoftDeleted() {
            WorkflowRun run = WorkflowRun.builder()
                    .id("r-1").workflowName("DeletedButRemembered").build();

            assertThat(safeWorkflowName(run)).isEqualTo("DeletedButRemembered");
        }
    }

    @Nested
    class SafeProjectName {

        @Test
        void withProject_returnsName() {
            Project project = new Project();
            project.setName("My Project");
            Workflow wf = Workflow.builder().id("wf-1").name("WF").project(project).build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).build();

            assertThat(safeProjectName(run)).isEqualTo("My Project");
        }

        @Test
        void noProject_returnsNull() {
            Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).build();

            assertThat(safeProjectName(run)).isNull();
        }

        @Test
        void nullWorkflow_returnsNull() {
            WorkflowRun run = WorkflowRun.builder().id("r-1").build();

            assertThat(safeProjectName(run)).isNull();
        }
    }

    @Nested
    class SafeProjectPath {

        @Test
        void withPath_returnsPath() {
            Project project = new Project();
            project.setPath("/home/user/project");
            Workflow wf = Workflow.builder().id("wf-1").name("WF").project(project).build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).build();

            assertThat(safeProjectPath(run)).isEqualTo("/home/user/project");
        }

        @Test
        void noPath_returnsNull() {
            Project project = new Project();
            project.setPath(null);
            Workflow wf = Workflow.builder().id("wf-1").name("WF").project(project).build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).build();

            assertThat(safeProjectPath(run)).isNull();
        }

        @Test
        void nullWorkflow_returnsNull() {
            WorkflowRun run = WorkflowRun.builder().id("r-1").build();

            assertThat(safeProjectPath(run)).isNull();
        }
    }
}
