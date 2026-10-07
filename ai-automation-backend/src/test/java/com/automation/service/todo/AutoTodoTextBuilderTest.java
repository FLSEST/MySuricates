package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.mapper.TodoMapper;
import com.automation.repository.StepResultRepository;
import com.automation.service.todo.GateStatusResolver.GateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Locks the text-building behaviour before extraction (Audit-TODO 9b4845ab). */
@ExtendWith(MockitoExtension.class)
class AutoTodoTextBuilderTest {

    @Mock private StepResultRepository stepResultRepository;
    private AutoTodoTextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AutoTodoTextBuilder(stepResultRepository);
    }

    private WorkflowRunCompletedEvent event(String workflowId, String runId) {
        return new WorkflowRunCompletedEvent(this, runId, workflowId, "Test Workflow", "proj-1", "/workspace",
                RunStatus.FAILED, Map.of(), Map.of(), Map.of());
    }

    private static StepResult step(int index, StepStatus status, String output) {
        return StepResult.builder().stepIndex(index).status(status).output(output).build();
    }

    private static String longOutput(String marker) {
        return marker + " " + "x".repeat(250);
    }

    @Test
    void loadReportFromSteps_fewerThanTwoSteps_returnsNull() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("r")).thenReturn(List.of(step(0, StepStatus.COMPLETED, longOutput("only"))));

        assertThat(builder.loadReportFromSteps("r")).isNull();
    }

    @Test
    void loadReportFromSteps_walksBackFromSecondToLast_skipsFirstAndLast() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("r")).thenReturn(List.of(
                step(0, StepStatus.COMPLETED, longOutput("first")),
                step(1, StepStatus.COMPLETED, longOutput("report")),
                step(2, StepStatus.COMPLETED, "short"),
                step(3, StepStatus.COMPLETED, longOutput("verdict"))));

        assertThat(builder.loadReportFromSteps("r")).startsWith("report");
    }

    @Test
    void loadReportFromSteps_ignoresNonCompletedSteps_andFirstStep() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("r")).thenReturn(List.of(
                step(0, StepStatus.COMPLETED, longOutput("first")),
                step(1, StepStatus.FAILED, longOutput("failed")),
                step(2, StepStatus.COMPLETED, longOutput("last"))));

        assertThat(builder.loadReportFromSteps("r")).isNull();
    }

    @Test
    void loadReportFromSteps_longOutput_isCutAt4000WithMarker() {
        String huge = "y".repeat(5000);
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("r")).thenReturn(List.of(
                step(0, StepStatus.COMPLETED, ""), step(1, StepStatus.COMPLETED, huge), step(2, StepStatus.COMPLETED, "")));

        String report = builder.loadReportFromSteps("r");

        assertThat(report).hasSize(4000 + "\n\n[...gekürzt]".length()).endsWith("\n\n[...gekürzt]");
    }

    @Test
    void loadReportFromSteps_repositoryThrows_returnsNull() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("r")).thenThrow(new RuntimeException("db"));

        assertThat(builder.loadReportFromSteps("r")).isNull();
    }

    @Test
    void buildSourceFooter_bothIds_markdownLinks() {
        assertThat(builder.buildSourceFooter(event("wf-1", "run-1")))
                .isEqualTo("*Source: [Test Workflow](/workflows/wf-1) · [Run →](/workflows/wf-1/run/run-1)*");
    }

    @Test
    void buildSourceFooter_missingWorkflowId_fallbackFormat() {
        assertThat(builder.buildSourceFooter(event(null, "run-1")))
                .isEqualTo("*Workflow: Test Workflow | Run: `run-1`*");
    }

    @Test
    void buildTagsJson_fixedOrder() {
        GateResult gate = new GateResult(GateStatus.RED, "lint", null);

        assertThat(builder.buildTagsJson(gate))
                .isEqualTo(TodoMapper.listToJson(List.of("auto:workflow", "gate:red", "category:lint")));
    }

    @Test
    void buildDescription_gateDescription_isAuthoritative_noStepLookup() {
        GateResult gate = new GateResult(GateStatus.YELLOW, "tests", "2 flaky tests");

        String d = builder.buildDescription(event("wf-1", "run-1"), gate);

        assertThat(d).isEqualTo("## YELLOW — tests\n\n2 flaky tests\n\n---\n"
                + "*Source: [Test Workflow](/workflows/wf-1) · [Run →](/workflows/wf-1/run/run-1)*");
        verifyNoInteractions(stepResultRepository);
    }

    @Test
    void buildDescription_withoutGateDescription_usesReportStep() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of(
                step(0, StepStatus.COMPLETED, ""), step(1, StepStatus.COMPLETED, longOutput("REPORT")), step(2, StepStatus.COMPLETED, "")));
        GateResult gate = new GateResult(GateStatus.RED, "build", "  ");

        String d = builder.buildDescription(event("wf-1", "run-1"), gate);

        assertThat(d).startsWith("## RED — build\n\nREPORT ");
        assertThat(d).endsWith("\n\n---\n*Source: [Test Workflow](/workflows/wf-1) · [Run →](/workflows/wf-1/run/run-1)*");
    }

    @Test
    void buildDescription_noGateDescriptionAndNoReport_headerAndFooterOnly() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of());

        assertThat(builder.buildDescription(event("wf-1", "run-1"), new GateResult(GateStatus.RED, "build", null)))
                .isEqualTo("## RED — build\n\n\n\n---\n*Source: [Test Workflow](/workflows/wf-1) · [Run →](/workflows/wf-1/run/run-1)*");
    }

    @Test
    void buildGranularDescription_withAndWithoutItemDescription() {
        assertThat(builder.buildGranularDescription("Fix it", event("wf-1", "run-1")))
                .isEqualTo("Fix it\n\n---\n*Source: [Test Workflow](/workflows/wf-1) · [Run →](/workflows/wf-1/run/run-1)*");
        assertThat(builder.buildGranularDescription("  ", event("wf-1", "run-1")))
                .isEqualTo("\n\n---\n*Source: [Test Workflow](/workflows/wf-1) · [Run →](/workflows/wf-1/run/run-1)*");
    }

    private static final LocalDateTime TRACK_TIME = LocalDateTime.of(2026, 10, 7, 9, 5);

    @Test
    void appendTrackUpdate_noReportStep_appendsGateDescriptionWithTimestampAndRun() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-2")).thenReturn(List.of());
        GateResult gate = new GateResult(GateStatus.YELLOW, "tests", "2 flaky tests");

        String d = builder.appendTrackUpdate("old text", event("wf-1", "run-2"), gate, TRACK_TIME);

        assertThat(d).isEqualTo("old text\n\n---\n### Update 2026-10-07 09:05\n*Run: `run-2`*\n\n2 flaky tests");
    }

    @Test
    void appendTrackUpdate_reportStepWins_overGateDescription() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-2")).thenReturn(List.of(
                step(0, StepStatus.COMPLETED, ""), step(1, StepStatus.COMPLETED, longOutput("REPORT")), step(2, StepStatus.COMPLETED, "")));
        GateResult gate = new GateResult(GateStatus.RED, "build", "gate text");

        String d = builder.appendTrackUpdate("old", event("wf-1", "run-2"), gate, TRACK_TIME);

        assertThat(d).startsWith("old\n\n---\n### Update 2026-10-07 09:05\n*Run: `run-2`*\n\nREPORT ");
        assertThat(d).doesNotContain("gate text");
    }

    @Test
    void appendTrackUpdate_nullCurrentDescription_isTreatedAsEmpty() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-2")).thenReturn(List.of());

        String d = builder.appendTrackUpdate(null, event("wf-1", "run-2"),
                new GateResult(GateStatus.GREEN, "ok", "fine"), TRACK_TIME);

        assertThat(d).isEqualTo("\n\n---\n### Update 2026-10-07 09:05\n*Run: `run-2`*\n\nfine");
    }

    @Test
    void appendTrackUpdate_overCap_dropsOldestPartAndRestartsAtUpdateSeparator() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-2")).thenReturn(List.of());
        String older = ("\n\n---\n### Update old\n" + "z".repeat(990)).repeat(9);
        assertThat(older.length()).isGreaterThan(AutoTodoTextBuilder.MAX_TRACK_DESCRIPTION_LENGTH);

        String d = builder.appendTrackUpdate(older, event("wf-1", "run-2"),
                new GateResult(GateStatus.RED, "build", "newest"), TRACK_TIME);

        assertThat(d).startsWith("...\n---\n### Update ");
        assertThat(d).endsWith("### Update 2026-10-07 09:05\n*Run: `run-2`*\n\nnewest");
        assertThat(d.length()).isLessThanOrEqualTo(AutoTodoTextBuilder.MAX_TRACK_DESCRIPTION_LENGTH + "...\n".length());
    }
}
