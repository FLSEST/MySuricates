package com.automation.integration.scenario;

import com.automation.entity.AuditFinding;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChainEntry;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowRun;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.persistence.EntityManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a scenario runs against (TODO 0fc5073b): its project, its workflows — the main one plus any
 * named in {@code workflows}, referenced as {@code "@name"} from block configs and chain entries —
 * and a chain. Also waits for the audit ingestion of a run and removes what the async listeners
 * wrote, so nothing leaks into other integration tests of the same JVM.
 */
final class ScenarioFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EntityManager entityManager;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowChainRepository chainRepository;
    private final AuditReportRepository auditReportRepository;
    private final AuditFindingRepository auditFindingRepository;
    private final AuditFindingOccurrenceRepository auditOccurrenceRepository;
    private final AuditFindingTodoRepository auditFindingTodoRepository;

    ScenarioFixtures(EntityManager entityManager, ProjectRepository projectRepository,
                     WorkflowRepository workflowRepository, WorkflowChainRepository chainRepository,
                     AuditReportRepository auditReportRepository, AuditFindingRepository auditFindingRepository,
                     AuditFindingOccurrenceRepository auditOccurrenceRepository,
                     AuditFindingTodoRepository auditFindingTodoRepository) {
        this.entityManager = entityManager;
        this.projectRepository = projectRepository;
        this.workflowRepository = workflowRepository;
        this.chainRepository = chainRepository;
        this.auditReportRepository = auditReportRepository;
        this.auditFindingRepository = auditFindingRepository;
        this.auditOccurrenceRepository = auditOccurrenceRepository;
        this.auditFindingTodoRepository = auditFindingTodoRepository;
    }

    Project project(Path baseDir, String id) {
        Path projectPath = baseDir.resolve(id);
        try {
            Files.createDirectories(projectPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return projectRepository.saveAndFlush(Project.builder()
                .name("scenario " + id + "-" + System.nanoTime())
                .type(ProjectType.CODE)
                .path(projectPath.toString())
                .status(ProjectStatus.STOPPED)
                .build());
    }

    /** The workflows named in {@code workflows}, in file order: name → id, for {@code "@name"} references. */
    Map<String, String> namedWorkflows(Project project, JsonNode workflows) {
        Map<String, String> ids = new LinkedHashMap<>();
        workflows.fields().forEachRemaining(entry ->
                ids.put(entry.getKey(), workflow(project, entry.getKey(), entry.getValue(), ids).getId()));
        return ids;
    }

    Workflow workflow(Project project, String name, JsonNode spec, Map<String, String> refs) {
        List<BlockDefinition> blocks = JSON.convertValue(resolveRefs(spec.path("blocks"), refs), new TypeReference<>() { });
        Map<String, Object> metadata = spec.has("metadata")
                ? JSON.convertValue(spec.path("metadata"), new TypeReference<>() { }) : new HashMap<>();
        Workflow saved = workflowRepository.saveAndFlush(Workflow.builder()
                .name("scenario " + name + "-" + System.nanoTime())
                .project(project)
                .blocks(new ArrayList<>(blocks))
                .provider(ScenarioChecks.text(spec, "provider"))
                .model(ScenarioChecks.text(spec, "model"))
                .instructions(ScenarioChecks.text(spec, "instructions"))
                .activatedSkills(new ArrayList<>())
                .defaultContext(Map.of())
                .metadata(metadata)
                .build());
        entityManager.clear();
        return workflowRepository.findByIdWithSkills(saved.getId()).orElseThrow();
    }

    /** {@code chain}: entries reference workflows as {@code "@name"}; {@code stopOnFailure} defaults to true. */
    WorkflowChain chain(String id, JsonNode spec, Map<String, String> refs) {
        List<ChainEntry> entries = new ArrayList<>();
        for (JsonNode entry : spec.path("entries")) {
            String ref = entry.path("workflow").asText();
            String workflowId = refs.get(ref.startsWith("@") ? ref.substring(1) : ref);
            if (workflowId == null) {
                throw new IllegalArgumentException("chain entry names unknown workflow " + ref);
            }
            Map<String, Object> context = entry.has("context")
                    ? JSON.convertValue(entry.path("context"), new TypeReference<>() { }) : new HashMap<>();
            entries.add(ChainEntry.builder().workflowId(workflowId).sortOrder(entries.size())
                    .stopOnFailure(entry.path("stopOnFailure").asBoolean(true)).inputContext(context).build());
        }
        return chainRepository.saveAndFlush(WorkflowChain.builder()
                .name("scenario " + id + "-" + System.nanoTime())
                .entries(entries)
                .stopOnFailure(spec.path("stopOnFailure").asBoolean(true))
                .build());
    }

    /**
     * An audit workflow's report is written by an async listener after the run. The next run of a
     * scenario must not start before it, or two ingestions race for the same findings.
     */
    void awaitAuditIngestion(Workflow workflow, WorkflowRun run) {
        Object audit = workflow.getMetadata() == null ? null : workflow.getMetadata().get("audit");
        if (!(audit instanceof Map<?, ?> config) || !Boolean.TRUE.equals(config.get("enabled"))) {
            return;
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!auditReportRepository.existsByWorkflowRunId(run.getId())) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("no audit report for run " + run.getId() + " after 10 s");
            }
            ScenarioChecks.sleep(100);
        }
    }

    /** Audit rows of the project: occurrences and TODO links first, they point at findings and reports. */
    void deleteAuditData(String projectId) {
        List<String> findingIds = new ArrayList<>();
        for (Object[] scope : auditFindingRepository.findDistinctScopes()) {
            if (projectId.equals(scope[0])) {
                auditFindingRepository.findByProjectIdAndAuditKey(projectId, (String) scope[1]).stream()
                        .map(AuditFinding::getId).forEach(findingIds::add);
            }
        }
        for (String findingId : findingIds) {
            auditOccurrenceRepository.deleteAll(auditOccurrenceRepository.findByFindingId(findingId));
        }
        auditFindingTodoRepository.deleteAll(auditFindingTodoRepository.findByFindingIdIn(findingIds));
        auditFindingRepository.deleteAllById(findingIds);
        auditReportRepository.deleteAll(auditReportRepository.findByProjectIdOrderByGeneratedAtDesc(projectId));
    }

    /** Replaces every text value {@code "@name"} of a named workflow by its id, anywhere in the tree. */
    static JsonNode resolveRefs(JsonNode node, Map<String, String> refs) {
        if (node.isTextual() && node.asText().startsWith("@") && refs.containsKey(node.asText().substring(1))) {
            return TextNode.valueOf(refs.get(node.asText().substring(1)));
        }
        if (node.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            node.fields().forEachRemaining(field -> copy.set(field.getKey(), resolveRefs(field.getValue(), refs)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            node.forEach(item -> copy.add(resolveRefs(item, refs)));
            return copy;
        }
        return node;
    }
}
