package com.automation.service.audit;

import com.automation.entity.*;
import com.automation.event.TodoCreatedEvent;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.mapper.TodoMapper;
import com.automation.repository.*;
import com.automation.service.audit.AuditResultParser.ParseResult;
import com.automation.service.audit.AuditResultParser.ParsedFinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditIngestionService {
    static final String REFERENCE_TYPE = "audit_finding";
    static final String SOURCE_AGENT = "audit-center";

    private final WorkflowRunRepository runRepository;
    private final AuditReportRepository reportRepository;
    private final AuditFindingRepository findingRepository;
    private final AuditFindingOccurrenceRepository occurrenceRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final TodoRepository todoRepository;
    private final AuditResultParser parser;
    private final AuditAcceptancePolicy acceptancePolicy;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void ingest(WorkflowRunCompletedEvent event) {
        if (reportRepository.existsByWorkflowRunId(event.getRunId())) return;

        AuditConfig config = AuditConfig.from(event.getWorkflowMetadata());
        WorkflowRun run = runRepository.findByIdWithSteps(event.getRunId()).orElse(null);
        if (run == null || config == null) {
            log.warn("Cannot ingest audit run {}: run or audit config missing", event.getRunId());
            return;
        }

        StepResult reportStep = run.getSteps().stream()
                .filter(step -> config.reportBlock().equals(String.valueOf(step.getMetadata().get("blockName"))))
                .findFirst().orElse(null);
        String output = reportStep != null ? reportStep.getOutput() : "";
        ParseResult parsed = parser.parse(output);
        boolean successful = run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.COMPLETED_WITH_ERRORS;
        String error = !successful ? "Run ended with status " + run.getStatus()
                : reportStep == null ? "Configured report block '" + config.reportBlock() + "' was not persisted"
                : reportStep.getStatus() != StepStatus.COMPLETED ? "Report block did not complete"
                : parsed.error();
        boolean valid = successful && reportStep != null && reportStep.getStatus() == StepStatus.COMPLETED && parsed.valid();

        AuditReport report = reportRepository.save(AuditReport.builder()
                .projectId(event.getProjectId())
                .workflowId(event.getWorkflowId())
                .workflowRunId(event.getRunId())
                .auditKey(config.key())
                .category(config.category())
                .title(event.getWorkflowName())
                .markdownContent(parsed.markdown())
                .gateStatus(parseGate(run.getMetadata().get("gate_status")))
                .gateDescription(asString(run.getMetadata().get("gate_description")))
                .provider(firstNonBlank(metadataValue(reportStep, "provider"), run.getWorkflow().getProvider()))
                .model(firstNonBlank(metadataValue(reportStep, "model"), run.getWorkflow().getModel()))
                .workflowVersion(run.getWorkflow().getVersion())
                .ingestionStatus(valid ? AuditIngestionStatus.VALID : AuditIngestionStatus.INVALID)
                .ingestionError(valid ? null : firstNonBlank(error, "Invalid audit snapshot"))
                .generatedAt(run.getCompletedAt() != null ? run.getCompletedAt() : Instant.now())
                .build());

        if (!valid) {
            log.warn("Stored invalid audit report for run {}: {}", event.getRunId(), error);
            return;
        }

        ingestSnapshot(event, report, parsed.findings());
    }

    private void ingestSnapshot(WorkflowRunCompletedEvent event, AuditReport report, List<ParsedFinding> parsedFindings) {
        Instant observedAt = report.getGeneratedAt();
        Set<String> seenFingerprints = new HashSet<>();
        List<AuditFinding> touched = new ArrayList<>();

        for (ParsedFinding item : parsedFindings) {
            String fingerprint = fingerprint(event.getProjectId(), report.getAuditKey(), item.ruleKey(), item.subject());
            if (!seenFingerprints.add(fingerprint)) continue;

            AuditFinding finding = findingRepository
                    .findByProjectIdAndAuditKeyAndFingerprint(event.getProjectId(), report.getAuditKey(), fingerprint)
                    .orElseGet(() -> AuditFinding.builder()
                            .projectId(event.getProjectId()).auditKey(report.getAuditKey()).fingerprint(fingerprint)
                            .ruleKey(item.ruleKey()).subject(item.subject()).status(AuditFindingStatus.OPEN)
                            .firstSeenReportId(report.getId()).firstSeenRunId(event.getRunId()).firstSeenAt(observedAt)
                            .occurrenceCount(0).build());

            if (finding.getId() != null) {
                if (finding.getStatus() == AuditFindingStatus.RESOLVED) {
                    finding.setRegressionCount(finding.getRegressionCount() + 1);
                }
                if (finding.getStatus() == AuditFindingStatus.RESOLVED
                        || finding.getStatus() == AuditFindingStatus.AWAITING_VERIFICATION) {
                    finding.setStatus(AuditFindingStatus.OPEN);
                    finding.setResolvedAt(null);
                }
                AuditAcceptancePolicy.Outcome outcome = acceptancePolicy.reassess(finding, item.evidence(), observedAt);
                if (outcome != AuditAcceptancePolicy.Outcome.UNCHANGED) {
                    log.info("Accepted audit finding {} ({}) re-assessed: {}", finding.getId(), finding.getTitle(), outcome);
                }
            }
            // subject ist reines Anzeigeattribut — der neueste Wert gewinnt, der Fingerprint haengt
            // an seiner normalisierten Form und bleibt dadurch ueber Drift hinweg stabil.
            finding.setSubject(item.subject());
            finding.setSeverity(item.severity());
            finding.setTitle(item.title());
            finding.setDescription(item.description());
            finding.setEvidence(item.evidence());
            finding.setTags(TodoMapper.listToJson(item.tags()));
            finding.setLastSeenReportId(report.getId());
            finding.setLastSeenRunId(event.getRunId());
            finding.setLastSeenAt(observedAt);
            finding.setOccurrenceCount(finding.getOccurrenceCount() + 1);
            finding = findingRepository.save(finding);
            touched.add(finding);

            if (!occurrenceRepository.existsByFindingIdAndReportId(finding.getId(), report.getId())) {
                occurrenceRepository.save(AuditFindingOccurrence.builder()
                        .findingId(finding.getId()).reportId(report.getId()).severity(item.severity())
                        .title(item.title()).description(item.description()).evidence(item.evidence())
                        .tags(TodoMapper.listToJson(item.tags())).observedAt(observedAt).build());
            }
            // Nur offene Findings bekommen ein verwaltetes TODO: akzeptierte/verworfene Risiken und
            // Wiedervorlagen (AWAITING_VERIFICATION nach Verfall) loesen keinen Fix-Loop aus.
            if ((item.severity() == AuditSeverity.CRITICAL || item.severity() == AuditSeverity.HIGH)
                    && finding.getStatus() == AuditFindingStatus.OPEN) {
                ensureManagedTodo(finding, report);
            }
        }

        for (AuditFinding finding : findingRepository.findByProjectIdAndAuditKey(event.getProjectId(), report.getAuditKey())) {
            if (!seenFingerprints.contains(finding.getFingerprint())
                    && (finding.getStatus() == AuditFindingStatus.OPEN
                    || finding.getStatus() == AuditFindingStatus.AWAITING_VERIFICATION)) {
                finding.setStatus(AuditFindingStatus.RESOLVED);
                finding.setResolvedAt(observedAt);
                touched.add(findingRepository.save(finding));
            }
        }
        reconcileManagedTodos(touched);
    }

    private Todo ensureManagedTodo(AuditFinding finding, AuditReport report) {
        Optional<AuditFindingTodo> existing = findingTodoRepository.findByFindingId(finding.getId()).stream()
                .filter(AuditFindingTodo::isManaged).findFirst();
        if (existing.isPresent()) {
            Todo linkedTodo = todoRepository.findById(existing.get().getTodoId()).orElse(null);
            if (linkedTodo != null) return linkedTodo;
            findingTodoRepository.delete(existing.get());
        }
        Todo todo = todoRepository.save(Todo.builder()
                .title(finding.getTitle())
                .description(buildTodoDescription(finding, report))
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.valueOf(finding.getSeverity().name()))
                .tags(TodoMapper.listToJson(List.of("audit", "audit:" + finding.getAuditKey(), "auto:workflow")))
                .projectId(finding.getProjectId()).source(Todo.Source.WORKFLOW).sourceAgent(SOURCE_AGENT)
                .workflowRunId(report.getWorkflowRunId()).referenceType(REFERENCE_TYPE).referenceId(finding.getId())
                .build());
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(finding.getId()).todoId(todo.getId()).managed(true).build());
        eventPublisher.publishEvent(new TodoCreatedEvent(this, todo));
        return todo;
    }

    private void reconcileManagedTodos(List<AuditFinding> touched) {
        List<String> ids = touched.stream().map(AuditFinding::getId).distinct().toList();
        if (ids.isEmpty()) return;
        for (AuditFindingTodo link : findingTodoRepository.findByFindingIdIn(ids)) {
            if (!link.isManaged()) continue;
            Todo todo = todoRepository.findById(link.getTodoId()).orElse(null);
            if (todo == null) continue;
            List<AuditFinding> linked = findingTodoRepository.findByTodoId(todo.getId()).stream()
                    .map(l -> findingRepository.findById(l.getFindingId()).orElse(null))
                    .filter(Objects::nonNull).toList();
            boolean allTerminal = !linked.isEmpty() && linked.stream().allMatch(f -> f.getStatus().isTerminal());
            if (allTerminal && todo.getStatus() != Todo.Status.DONE) {
                todo.setStatus(Todo.Status.DONE);
                todoRepository.save(todo);
            } else if (!allTerminal && todo.getStatus() == Todo.Status.DONE) {
                todo.setStatus(Todo.Status.OPEN);
                todo.setWorkflowRunId(linked.stream().map(AuditFinding::getLastSeenRunId).filter(Objects::nonNull).findFirst().orElse(null));
                todoRepository.save(todo);
            }
        }
    }

    private static String buildTodoDescription(AuditFinding finding, AuditReport report) {
        String detail = finding.getDescription() != null ? finding.getDescription() : "Audit finding requires remediation.";
        return detail + "\n\nEvidence: " + (finding.getEvidence() != null ? finding.getEvidence() : "not provided")
                + "\n\n[Audit finding](/reports?finding=" + finding.getId() + ")"
                + " · [Evidence run](/workflows/" + report.getWorkflowId() + "/run/" + report.getWorkflowRunId() + ")";
    }

    /**
     * Identitaet eines Findings: Projekt, Audit, Regel und das <em>normalisierte</em> subject
     * ({@link AuditSubjectNormalizer}) — modellgenerierte Suffix-Drift im subject erzeugt damit
     * keinen neuen Fingerprint mehr (Audit-TODO 0e19648d).
     */
    static String fingerprint(String projectId, String auditKey, String ruleKey, String subject) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    String.join("\n", projectId, auditKey, ruleKey, AuditSubjectNormalizer.normalize(subject))
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static GateStatus parseGate(Object value) {
        try { return value != null ? GateStatus.valueOf(String.valueOf(value)) : null; }
        catch (IllegalArgumentException ex) { return null; }
    }
    private static String metadataValue(StepResult step, String key) {
        return step != null ? asString(step.getMetadata().get(key)) : null;
    }
    private static String asString(Object value) { return value != null ? String.valueOf(value) : null; }
    private static String firstNonBlank(String... values) {
        return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    record AuditConfig(String key, String category, String reportBlock, int schemaVersion) {
        static AuditConfig from(Map<String, Object> metadata) {
            Object value = metadata.get("audit");
            if (!(value instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("enabled"))) return null;
            String key = asString(map.get("key"));
            String category = asString(map.get("category"));
            String reportBlock = asString(map.get("reportBlock"));
            int version = map.get("schemaVersion") instanceof Number n ? n.intValue() : 1;
            if (key == null || category == null || reportBlock == null || version != 1) return null;
            return new AuditConfig(key, category, reportBlock, version);
        }
    }
}
