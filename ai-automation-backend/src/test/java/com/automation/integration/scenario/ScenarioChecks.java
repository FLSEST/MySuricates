package com.automation.integration.scenario;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditReport;
import com.automation.entity.ChainRun;
import com.automation.entity.Todo;
import com.automation.entity.WorkflowRun;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code expect} half of a scenario (TODO fdf79a86): each method checks one key against the run
 * that {@link ScenarioIntegrationTest} started. Kept apart from the runner, so a new key does not
 * grow it; {@code scenarios/README.md} documents every key.
 */
final class ScenarioChecks {

    private final EntityManager entityManager;
    private final WorkflowRunRepository workflowRunRepository;
    private final StepResultRepository stepResultRepository;
    private final TodoRepository todoRepository;
    private final AuditReportRepository auditReportRepository;
    private final AuditFindingRepository auditFindingRepository;
    private final List<ScriptedAiProvider> scriptedProviders;

    ScenarioChecks(EntityManager entityManager, WorkflowRunRepository workflowRunRepository,
                   StepResultRepository stepResultRepository, TodoRepository todoRepository,
                   AuditReportRepository auditReportRepository, AuditFindingRepository auditFindingRepository,
                   List<ScriptedAiProvider> scriptedProviders) {
        this.entityManager = entityManager;
        this.workflowRunRepository = workflowRunRepository;
        this.stepResultRepository = stepResultRepository;
        this.todoRepository = todoRepository;
        this.auditReportRepository = auditReportRepository;
        this.auditFindingRepository = auditFindingRepository;
        this.scriptedProviders = scriptedProviders;
    }

    /** Everything one run is checked for: status, error, gate, steps, markers, audit, AI calls, TODOs. */
    void checkRun(String projectId, WorkflowRun run, JsonNode expect) {
        if (expect.has("runStatus")) {
            assertThat(run.getStatus().name()).as("run status").isEqualTo(expect.path("runStatus").asText());
        }
        checkRunError(run, expect.path("runError"));
        checkGate(run, expect.path("gate"));
        checkSteps(run, expect.path("steps"));
        checkMarkers(run, expect.path("markers"));
        checkAudit(projectId, run, expect);
        checkAiCalls(expect);
        checkTodos(projectId, expect);
        checkDashboard(run, expect.path("dashboard"));
    }

    /**
     * {@code dashboard}: what the run reported to its dashboard tile (run metadata
     * {@code dashboard_widget}, TODO 259f4f66) — status, title, summary parts, KPI values by label and
     * item parts (each one must appear in some item).
     */
    @SuppressWarnings("unchecked")
    private void checkDashboard(WorkflowRun run, JsonNode want) {
        if (want.isMissingNode()) {
            return;
        }
        Map<String, Object> metadata = run.getMetadata() == null ? Map.of() : run.getMetadata();
        assertThat(metadata).as("run metadata — dashboard_widget expected").containsKey("dashboard_widget");
        Map<String, Object> widget = (Map<String, Object>) metadata.get("dashboard_widget");
        if (want.has("status")) {
            assertThat(widget.get("status")).as("dashboard status").isEqualTo(want.path("status").asText());
        }
        if (want.has("title")) {
            assertThat(widget.get("title")).as("dashboard title").isEqualTo(want.path("title").asText());
        }
        for (JsonNode part : want.path("summaryContains")) {
            assertThat(String.valueOf(widget.get("summary"))).as("dashboard summary").contains(part.asText());
        }
        List<Map<String, Object>> kpis = widget.get("kpis") == null ? List.of() : (List<Map<String, Object>>) widget.get("kpis");
        want.path("kpis").fields().forEachRemaining(kpi -> assertThat(kpis.stream()
                        .filter(entry -> kpi.getKey().equals(entry.get("label"))).map(entry -> entry.get("value")).toList())
                .as("dashboard KPI " + kpi.getKey()).containsExactly(kpi.getValue().asText()));
        List<String> items = widget.get("items") == null ? List.of() : (List<String>) widget.get("items");
        for (JsonNode part : want.path("itemsContain")) {
            assertThat(items).as("dashboard items").anySatisfy(item -> assertThat(item).contains(part.asText()));
        }
    }

    /** A chain: its status, each entry's run like a single run ({@code entries}), and the AI calls of all. */
    void checkChain(String projectId, ChainRun chainRun, JsonNode expect) {
        if (expect.has("chainStatus")) {
            assertThat(chainRun.getStatus().name()).as("chain status").isEqualTo(expect.path("chainStatus").asText());
        }
        JsonNode entries = expect.path("entries");
        for (int i = 0; i < entries.size(); i++) {
            assertThat(chainRun.getEntryRuns().size()).as("entry runs of the chain — entry #%d expected", i).isGreaterThan(i);
            String runId = chainRun.getEntryRuns().get(i).getWorkflowRunId();
            assertThat(runId).as("entry #%d started a run", i).isNotNull();
            entityManager.clear();
            checkRun(projectId, workflowRunRepository.findById(runId).orElseThrow(), entries.get(i));
        }
        checkAiCalls(expect);
        checkTodos(projectId, expect);
    }

    /** {@code runError}: the message a failed run reports — where it failed comes first. */
    private void checkRunError(WorkflowRun run, JsonNode want) {
        if (want.isMissingNode()) {
            return;
        }
        Map<String, Object> error = run.getError() == null ? Map.of() : run.getError();
        String message = error.get("message") == null ? "" : error.get("message").toString();
        if (want.has("startsWith")) {
            assertThat(message).as("run error").startsWith(want.path("startsWith").asText());
        }
        for (JsonNode part : want.path("contains")) {
            assertThat(message).as("run error").contains(part.asText());
        }
        for (JsonNode part : want.path("notContains")) {
            assertThat(message).as("run error").doesNotContain(part.asText());
        }
    }

    private void checkGate(WorkflowRun run, JsonNode gate) {
        if (gate.isMissingNode()) {
            return;
        }
        Map<String, Object> metadata = run.getMetadata() == null ? Map.of() : run.getMetadata();
        if (gate.isTextual() && "none".equals(gate.asText())) {
            assertThat(metadata).as("run metadata — no gate expected").doesNotContainKey("gate_status");
            return;
        }
        assertThat(metadata.get("gate_status")).as("gate_status").isEqualTo(gate.path("status").asText());
        if (gate.has("category")) {
            assertThat(metadata.get("gate_category")).as("gate_category").isEqualTo(gate.path("category").asText());
        }
    }

    private void checkSteps(WorkflowRun run, JsonNode steps) {
        if (steps.isMissingNode()) {
            return;
        }
        entityManager.clear();
        var persisted = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId());
        for (JsonNode want : steps) {
            int index = want.path("index").asInt();
            assertThat(persisted.size()).as("steps of the run — step #%d expected", index).isGreaterThan(index);
            var step = persisted.get(index);
            String where = "step #" + index;
            if (want.has("status")) {
                assertThat(step.getStatus().name()).as(where + " status").isEqualTo(want.path("status").asText());
            }
            String output = step.getOutput() == null ? "" : step.getOutput();
            for (JsonNode part : want.path("outputContains")) {
                assertThat(output).as(where + " output").contains(part.asText());
            }
            for (JsonNode part : want.path("outputNotContains")) {
                assertThat(output).as(where + " output").doesNotContain(part.asText());
            }
        }
    }

    /** {@code markers}: gate_status and todo_item markers the run counted — a copy must not count again. */
    private static void checkMarkers(WorkflowRun run, JsonNode want) {
        if (want.isMissingNode()) {
            return;
        }
        Map<String, Object> metadata = run.getMetadata() == null ? Map.of() : run.getMetadata();
        if (want.has("gates")) {
            int gates = metadata.get("gate_summary") instanceof Map<?, ?> summary
                    && summary.get("items") instanceof List<?> items ? items.size() : 0;
            assertThat(gates).as("gate markers counted (gate_summary.items)").isEqualTo(want.path("gates").asInt());
        }
        if (want.has("todos")) {
            int todos = metadata.get("todo_items") instanceof List<?> items ? items.size() : 0;
            assertThat(todos).as("todo_item markers counted (todo_items)").isEqualTo(want.path("todos").asInt());
        }
    }

    /** {@code auditReport}, {@code auditFindingCount}, {@code auditFindings}: what the ingestion made of the run. */
    private void checkAudit(String projectId, WorkflowRun run, JsonNode expect) {
        if (expect.has("auditReport")) {
            AuditReport report = auditReportRepository.findByProjectIdOrderByGeneratedAtDesc(projectId).stream()
                    .filter(candidate -> run.getId().equals(candidate.getWorkflowRunId())).findFirst().orElse(null);
            assertThat(report).as("audit report of the run").isNotNull();
            assertThat(report.getIngestionStatus().name()).as("audit report ingestion status (%s)", report.getIngestionError())
                    .isEqualTo(expect.path("auditReport").asText());
        }
        if (!expect.has("auditFindingCount") && !expect.has("auditFindings")) {
            return;
        }
        entityManager.clear();
        List<AuditFinding> findings = new ArrayList<>();
        for (Object[] scope : auditFindingRepository.findDistinctScopes()) {
            if (projectId.equals(scope[0])) {
                findings.addAll(auditFindingRepository.findByProjectIdAndAuditKey(projectId, (String) scope[1]));
            }
        }
        if (expect.has("auditFindingCount")) {
            assertThat(findings).as("audit findings of the project — one per identity")
                    .hasSize(expect.path("auditFindingCount").asInt());
        }
        for (JsonNode want : expect.path("auditFindings")) {
            String subject = want.path("subject").asText();
            List<AuditFinding> matching = findings.stream().filter(finding -> subject.equals(finding.getSubject())).toList();
            assertThat(matching).as("audit findings with subject '%s'", subject).hasSize(1);
            AuditFinding finding = matching.get(0);
            String where = "audit finding '" + subject + "'";
            if (want.has("status")) {
                assertThat(finding.getStatus().name()).as(where + " status").isEqualTo(want.path("status").asText());
            }
            if (want.has("occurrenceCount")) {
                assertThat(finding.getOccurrenceCount()).as(where + " occurrenceCount").isEqualTo(want.path("occurrenceCount").asInt());
            }
            if (want.has("regressionCount")) {
                assertThat(finding.getRegressionCount()).as(where + " regressionCount").isEqualTo(want.path("regressionCount").asInt());
            }
        }
    }

    private void checkAiCalls(JsonNode expect) {
        for (ScriptedAiProvider provider : scriptedProviders) {
            List<ScriptedAiProvider.Call> calls = provider.calls();
            JsonNode count = expect.path("aiCallCount").path(provider.getId());
            if (!count.isMissingNode()) {
                assertThat(calls).as("calls to %s", provider.getId()).hasSize(count.asInt());
            }
            JsonNode expected = expect.path("aiCalls").path(provider.getId());
            for (int i = 0; i < expected.size(); i++) {
                JsonNode want = expected.get(i);
                assertThat(calls.size()).as("calls to %s — call #%d expected", provider.getId(), i).isGreaterThan(i);
                ScriptedAiProvider.Call call = calls.get(i);
                String where = provider.getId() + " call #" + i;
                if (want.has("model")) {
                    assertThat(call.model()).as(where + " model").isEqualTo(text(want, "model"));
                }
                if (want.has("timeoutSeconds")) {
                    assertThat(call.timeoutSeconds()).as(where + " timeoutSeconds").isEqualTo(want.path("timeoutSeconds").asInt());
                }
                if (want.has("effort")) {
                    assertThat(call.effort()).as(where + " effort").isEqualTo(text(want, "effort"));
                }
                for (JsonNode part : want.path("promptContains")) {
                    assertThat(call.prompt()).as(where + " prompt").contains(part.asText());
                }
                for (JsonNode part : want.path("promptNotContains")) {
                    assertThat(call.prompt()).as(where + " prompt").doesNotContain(part.asText());
                }
            }
        }
    }

    /** Auto-TODOs are written by an async listener after the run — polled, not read once. */
    private void checkTodos(String projectId, JsonNode expect) {
        if (expect.path("noTodos").asBoolean(false)) {
            sleep(1_000); // long enough for the async listener to have written one if it would
            assertThat(todosOf(projectId)).as("todos of the scenario project").isEmpty();
        }
        for (JsonNode want : expect.path("todos")) {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            List<Todo> matching = List.of();
            while (Instant.now().isBefore(deadline)) {
                matching = todosOf(projectId).stream().filter(todo -> matches(todo, want)).toList();
                if (!matching.isEmpty()) {
                    break;
                }
                sleep(100);
            }
            assertThat(matching).as("todo %s among %s", want, todosOf(projectId).stream()
                    .map(todo -> todo.getTitle() + " " + todo.getTags() + " " + todo.getPriority()).toList()).isNotEmpty();
        }
    }

    private List<Todo> todosOf(String projectId) {
        entityManager.clear();
        return todoRepository.findByProjectIdAndStatusIn(projectId, Arrays.asList(Todo.Status.values()));
    }

    private static boolean matches(Todo todo, JsonNode want) {
        if (want.has("titleContains") && (todo.getTitle() == null || !todo.getTitle().contains(want.path("titleContains").asText()))) {
            return false;
        }
        if (want.has("priority") && !want.path("priority").asText().equals(String.valueOf(todo.getPriority()))) {
            return false;
        }
        for (JsonNode tag : want.path("tags")) {
            if (todo.getTags() == null || !todo.getTags().contains("\"" + tag.asText() + "\"")) {
                return false;
            }
        }
        return true;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
