package com.automation.service.assistant.context;

import com.automation.dto.AssistantChatRequest;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class WorkflowsIndexSectionTest {

    @Mock private WorkflowRepository workflowRepository;

    private WorkflowsIndexSection section;

    @BeforeEach
    void setUp() {
        section = new WorkflowsIndexSection(workflowRepository);
    }

    private Workflow buildWorkflow(String id, String name, Project project) {
        return Workflow.builder().id(id).name(name).project(project).build();
    }

    @Test
    void emptyList_appendsNothing() {
        StringBuilder sb = new StringBuilder();
        section.append(sb, false, List.of(), null);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void compact_showsTotals() {
        Project alpha = Project.builder().id("p-alpha").name("Alpha").build();
        Project beta = Project.builder().id("p-beta").name("Beta").build();
        List<Workflow> workflows = List.of(
                buildWorkflow("wf1", "Build", alpha),
                buildWorkflow("wf2", "Deploy", beta));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true, workflows, null);

        assertThat(sb.toString()).isEqualTo("**Workflows:** 2 total (2 projects)\n");
    }

    @Test
    void full_listsWorkflowsWithProjectAndLink() {
        Project alpha = Project.builder().id("p-alpha").name("Alpha").build();
        List<Workflow> workflows = List.of(buildWorkflow("wf1", "Build", alpha));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false, workflows, null);

        assertThat(sb.toString())
                .contains("**My Workflows (1):**")
                .contains("[Build] Alpha (project: p-alpha)")
                .contains("/workflows/wf1");
    }

    @Test
    void full_focusProject_listedFirst() {
        Project alpha = Project.builder().id("p-alpha").name("Alpha").build();
        Project beta = Project.builder().id("p-beta").name("Beta").build();
        List<Workflow> workflows = List.of(
                buildWorkflow("wf-beta", "A Beta Workflow", beta),
                buildWorkflow("wf-alpha", "Z Alpha Workflow", alpha));

        var ctx = new AssistantChatRequest.PageContext("workflows", null, "p-alpha");
        StringBuilder sb = new StringBuilder();
        section.append(sb, false, workflows, ctx);

        String result = sb.toString();
        assertThat(result.indexOf("Z Alpha Workflow")).isLessThan(result.indexOf("A Beta Workflow"));
    }

    @Test
    void full_moreThan25_showsOverflowHint() {
        Project alpha = Project.builder().id("p-alpha").name("Alpha").build();
        List<Workflow> workflows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            workflows.add(buildWorkflow("wf" + i, "Workflow %02d".formatted(i), alpha));
        }

        StringBuilder sb = new StringBuilder();
        section.append(sb, false, workflows, null);

        assertThat(sb.toString())
                .contains("**My Workflows (30):**")
                .contains("… and 5 more.");
    }

    @Test
    void full_workflowWithInputs_showsInputCountAndHint() {
        Project alpha = Project.builder().id("p-alpha").name("Alpha").build();
        Workflow wf = Workflow.builder().id("wf1").name("Build").project(alpha)
                .defaultContext(Map.of("target", "prod")).build();

        StringBuilder sb = new StringBuilder();
        section.append(sb, false, List.of(wf), null);

        assertThat(sb.toString())
                .contains("— 1 inputs")
                .contains("A workflow's inputs via <fetch type=\"workflow-inputs\"");
    }
}
