package com.automation.mapper;

import com.automation.dto.response.StepResultResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunListItemResponse;
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
    @Override public WorkflowRunResponse toRunResponse(WorkflowRun run, Map<String, RunOwner> owners) { return null; }
    @Override public List<WorkflowRunResponse> toRunResponseList(List<WorkflowRun> runs, Map<String, RunOwner> owners) { return null; }

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

    /**
     * The batched list path must produce exactly what the single path produces — it only takes the
     * labels from a map instead of from the lazy association (TODO 9db34f3b).
     */
    @Nested
    class BatchedOwners {

        private final RunOwner owner = new RunOwner("My Workflow", "My Project", "/home/user/project");

        @Test
        void knownWorkflow_returnsItsLabels() {
            Workflow wf = Workflow.builder().id("wf-1").name("My Workflow").build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).build();

            RunOwner resolved = ownerOf(run, Map.of("wf-1", owner));

            assertThat(resolved.projectName()).isEqualTo("My Project");
            assertThat(resolved.projectPath()).isEqualTo("/home/user/project");
        }

        @Test
        void unknownWorkflow_returnsEmptyLabels() {
            Workflow wf = Workflow.builder().id("wf-gone").name("WF").build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).build();

            RunOwner resolved = ownerOf(run, Map.of("wf-1", owner));

            assertThat(resolved.projectName()).isNull();
            assertThat(resolved.projectPath()).isNull();
        }

        @Test
        void nullWorkflow_returnsEmptyLabels() {
            WorkflowRun run = WorkflowRun.builder().id("r-1").build();

            assertThat(ownerOf(run, Map.of("wf-1", owner)).projectName()).isNull();
        }

        @Test
        void snapshotWins_likeInTheSinglePath() {
            Workflow wf = Workflow.builder().id("wf-1").name("My Workflow").build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).workflowName("Snapshot").build();

            assertThat(batchedWorkflowName(run, Map.of("wf-1", owner))).isEqualTo("Snapshot");
        }

        @Test
        void blankSnapshot_fallsBackToTheBatchedName() {
            Workflow wf = Workflow.builder().id("wf-1").name("My Workflow").build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).workflowName("  ").build();

            assertThat(batchedWorkflowName(run, Map.of("wf-1", owner))).isEqualTo("My Workflow");
        }

        /** Soft-deleted workflows are missing from the batch query — same wording as the single path. */
        @Test
        void missingWorkflowAndNoSnapshot_readsAsDeleted() {
            Workflow wf = Workflow.builder().id("wf-gone").name("WF").build();
            WorkflowRun run = WorkflowRun.builder().id("r-1").workflow(wf).build();

            assertThat(batchedWorkflowName(run, Map.of())).isEqualTo("(deleted)");
        }
    }

    /**
     * Die schlanke Listen-Antwort (TODO a05c9504, 7fd9f654).
     *
     * <p>Die Listen-Route trug jeden Step-Output mit: 3 061 816 Bytes bei 60 Runs, nach der
     * Umstellung 51 716. Statt der Schritte kommen ihre Anzahl und die gekuerzte Ausgabe des
     * letzten — seit 7fd9f654 aus einer Zusammenfassung, die die Datenbank liefert, ohne dass eine
     * Step-Zeile geladen wird. {@link #summaryOf} baut sie hier so, wie die beiden Abfragen sie
     * liefern: Anzahl, Status des letzten Schritts und dessen Ausgabe auf Vorschau + 1 Zeichen
     * gekuerzt. Die Abfragen selbst prueft {@code RequestCostRatchetTest} gegen die echte Datenbank.
     */
    @Nested
    class SchlankeListenAntwort {

        private static final WorkflowRun RUN = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build();

        private RunStepSummary summaryOf(String... outputs) {
            String last = outputs[outputs.length - 1];
            String head = last == null ? null
                    : last.substring(0, Math.min(last.length(), WorkflowRunListItemResponse.PREVIEW_LENGTH + 1));
            return new RunStepSummary(outputs.length, StepStatus.FAILED, head);
        }

        private WorkflowRunListItemResponse itemFor(RunStepSummary steps) {
            return toRunListItems(List.of(RUN), Map.of(), Map.of("run-1", steps)).get(0);
        }

        @Test
        void traegtAnzahlUndDenLetztenSchritt_nichtDieSchritteSelbst() {
            var item = itemFor(summaryOf("erster", "zweiter", "dritter"));

            assertThat(item.stepCount()).isEqualTo(3);
            assertThat(item.lastStepStatus()).isEqualTo(StepStatus.FAILED);
            assertThat(item.lastStepPreview()).isEqualTo("dritter");
        }

        @Test
        void langeAusgabeWirdGekuerzt_damitDieListeNichtWiederWaechst() {
            var item = itemFor(summaryOf("x".repeat(20_000)));

            assertThat(item.lastStepPreview())
                    .hasSize(WorkflowRunListItemResponse.PREVIEW_LENGTH + " […]".length())
                    .endsWith(" […]");
        }

        /** Genau Vorschau-Laenge ist nicht gekuerzt — das eine Zeichen mehr aus der Abfrage entscheidet. */
        @Test
        void ausgabeGenauInVorschauLaenge_bleibtOhneKuerzungsmarke() {
            String exact = "y".repeat(WorkflowRunListItemResponse.PREVIEW_LENGTH);

            assertThat(itemFor(summaryOf(exact)).lastStepPreview()).isEqualTo(exact);
            assertThat(itemFor(summaryOf(exact + "z")).lastStepPreview()).endsWith(" […]");
        }

        @Test
        void kurzeAusgabeBleibtVollstaendig_ohneKuerzungsmarke() {
            assertThat(itemFor(summaryOf("kurz")).lastStepPreview()).isEqualTo("kurz");
        }

        /** Kein Wert bleibt null — sonst sieht "nichts ausgegeben" wie "leer ausgegeben" aus. */
        @Test
        void ausgabeNull_bleibtNull() {
            var item = itemFor(summaryOf((String) null));

            assertThat(item.lastStepPreview()).isNull();
            assertThat(item.stepCount()).isEqualTo(1);
        }

        @Test
        void runOhneSchritte_zaehltNullUndLaesstDenLetztenLeer() {
            var item = toRunListItems(List.of(RUN), Map.of(), Map.of()).get(0);

            assertThat(item.stepCount()).isZero();
            assertThat(item.lastStepStatus()).isNull();
            assertThat(item.lastStepPreview()).isNull();
        }
    }

}
