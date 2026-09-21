package com.automation.service.audit;

import com.automation.dto.response.PageResponse;
import com.automation.entity.*;
import com.automation.event.TodoCreatedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.repository.*;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditCenterService {
    private final AuditReportRepository reportRepository;
    private final AuditFindingRepository findingRepository;
    private final AuditFindingOccurrenceRepository occurrenceRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final TodoRepository todoRepository;
    private final AuditAcceptancePolicy acceptancePolicy;
    private final ApplicationEventPublisher eventPublisher;
    private final ManagedTodoReconciler managedTodoReconciler;

    @Transactional(readOnly = true)
    public List<AuditSummary> listAudits(String projectId, String category, GateStatus gateStatus) {
        List<AuditReport> all = reportRepository.findAll(Sort.by(Sort.Direction.DESC, "generatedAt"));
        if (projectId != null) all = all.stream().filter(r -> projectId.equals(r.getProjectId())).toList();
        if (category != null) all = all.stream().filter(r -> category.equals(r.getCategory())).toList();

        Map<String, List<AuditReport>> grouped = all.stream().collect(Collectors.groupingBy(
                r -> r.getProjectId() + "\u0000" + r.getAuditKey(), LinkedHashMap::new, Collectors.toList()));
        List<AuditSummary> result = new ArrayList<>();
        for (List<AuditReport> reports : grouped.values()) {
            List<AuditReport> valid = reports.stream()
                    .filter(r -> r.getIngestionStatus() == AuditIngestionStatus.VALID).toList();
            if (valid.isEmpty()) continue;
            AuditReport latest = valid.getFirst();
            if (gateStatus != null && latest.getGateStatus() != gateStatus) continue;
            AuditReport previous = valid.size() > 1 ? valid.get(1) : null;
            List<AuditFinding> findings = findingRepository.findByProjectIdAndAuditKey(latest.getProjectId(), latest.getAuditKey());
            result.add(toSummary(latest, previous, reports.getFirst(), findings));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public AuditDetail getAudit(String projectId, String auditKey) {
        AuditReport latest = reportRepository
                .findTopByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
                        projectId, auditKey, AuditIngestionStatus.VALID)
                .orElseThrow(() -> new ResourceNotFoundException("Audit", auditKey));
        List<AuditReport> validHistory = reportRepository
                .findByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
                        projectId, auditKey, AuditIngestionStatus.VALID, PageRequest.of(0, 2))
                .getContent();
        AuditReport previous = validHistory.stream().filter(r -> !r.getId().equals(latest.getId()))
                .findFirst().orElse(null);
        AuditReport latestAttempt = reportRepository.findTopByProjectIdAndAuditKeyOrderByGeneratedAtDesc(projectId, auditKey)
                .orElse(latest);
        List<AuditFinding> findings = findingRepository.findByProjectIdAndAuditKey(projectId, auditKey);
        return new AuditDetail(toSummary(latest, previous, latestAttempt, findings), toReport(latest));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditReportResponse> listReports(String projectId, String auditKey, int page, int size) {
        Page<AuditReport> result = reportRepository.findByProjectIdAndAuditKeyOrderByGeneratedAtDesc(
                projectId, auditKey, PageRequest.of(page, size));
        return new PageResponse<>(result.getContent().stream().map(this::toReport).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AuditReportResponse getReport(String id) {
        return toReport(reportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditReport", id)));
    }

    @Transactional(readOnly = true)
    public AuditFindingResponse getFinding(String id) {
        AuditFinding finding = requireFinding(id);
        return toFinding(finding, todoLinks(List.of(id)).getOrDefault(id, List.of()));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditFindingResponse> listFindings(int page, int size, String projectId, String auditKey,
                                                           AuditFindingStatus status, AuditSeverity severity) {
        Specification<AuditFinding> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (projectId != null) predicates.add(cb.equal(root.get("projectId"), projectId));
            if (auditKey != null) predicates.add(cb.equal(root.get("auditKey"), auditKey));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (severity != null) predicates.add(cb.equal(root.get("severity"), severity));
            // Severity steht als Text in der DB; alphabetisch käme LOW vor MEDIUM (TODO f4ddc836).
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.orderBy(cb.asc(cb.selectCase(root.get("severity"))
                                .when(AuditSeverity.CRITICAL, 0).when(AuditSeverity.HIGH, 1)
                                .when(AuditSeverity.MEDIUM, 2).otherwise(3)),
                        cb.desc(root.get("lastSeenAt")));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<AuditFinding> result = findingRepository.findAll(spec, PageRequest.of(page, size));
        Map<String, List<TodoLink>> links = todoLinks(result.getContent().stream().map(AuditFinding::getId).toList());
        List<AuditFindingResponse> content = result.getContent().stream()
                .map(f -> toFinding(f, links.getOrDefault(f.getId(), List.of()))).toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public AuditFindingResponse updateFinding(String id, AuditFindingStatus status, String dispositionNote) {
        AuditFinding finding = requireFinding(id);
        if (status != null) {
            Instant now = Instant.now();
            finding.setStatus(status);
            finding.setResolvedAt(status.isTerminal() ? now : null);
            if (status == AuditFindingStatus.ACCEPTED_RISK) acceptancePolicy.accept(finding, now);
        }
        if (dispositionNote != null) finding.setDispositionNote(dispositionNote);
        finding = findingRepository.save(finding);
        managedTodoReconciler.reconcileFinding(id);
        return toFinding(finding, todoLinks(List.of(id)).getOrDefault(id, List.of()));
    }

    @Transactional
    public AuditFindingResponse createManagedTodo(String findingId) {
        AuditFinding finding = requireFinding(findingId);
        if (findingTodoRepository.findByFindingId(findingId).stream().noneMatch(AuditFindingTodo::isManaged)) {
            Todo todo = todoRepository.save(Todo.builder()
                    .title(finding.getTitle())
                    .description(managedTodoDescription(finding))
                    .status(Todo.Status.OPEN)
                    .priority(Todo.Priority.valueOf(finding.getSeverity().name()))
                    .tags(TodoMapper.listToJson(List.of("audit", "audit:" + finding.getAuditKey(), "auto:workflow")))
                    .projectId(finding.getProjectId()).source(Todo.Source.WORKFLOW)
                    .sourceAgent(ManagedTodoWriter.SOURCE_AGENT).workflowRunId(finding.getLastSeenRunId())
                    .referenceType(ManagedTodoWriter.REFERENCE_TYPE).referenceId(finding.getId()).build());
            findingTodoRepository.save(AuditFindingTodo.builder()
                    .findingId(findingId).todoId(todo.getId()).managed(true).build());
            eventPublisher.publishEvent(new TodoCreatedEvent(this, todo));
        }
        return toFinding(finding, todoLinks(List.of(findingId)).getOrDefault(findingId, List.of()));
    }

    @Transactional
    public AuditFindingResponse linkTodo(String findingId, String todoId) {
        AuditFinding finding = requireFinding(findingId);
        Todo todo = todoRepository.findById(todoId).orElseThrow(() -> new ResourceNotFoundException("Todo", todoId));
        if (!Objects.equals(finding.getProjectId(), todo.getProjectId())) {
            throw new IllegalArgumentException("Finding and TODO must belong to the same project");
        }
        findingTodoRepository.findByFindingIdAndTodoId(findingId, todoId)
                .orElseGet(() -> findingTodoRepository.save(AuditFindingTodo.builder()
                        .findingId(findingId).todoId(todoId).managed(false).build()));
        return toFinding(finding, todoLinks(List.of(findingId)).getOrDefault(findingId, List.of()));
    }

    @Transactional
    public AuditFindingResponse unlinkTodo(String findingId, String todoId) {
        AuditFinding finding = requireFinding(findingId);
        findingTodoRepository.findByFindingIdAndTodoId(findingId, todoId).ifPresent(findingTodoRepository::delete);
        clearAuditReference(findingId, todoId);
        return toFinding(finding, todoLinks(List.of(findingId)).getOrDefault(findingId, List.of()));
    }

    /** Sonst zeigt {@code todo.referenceId} (Deep-Link {@code /reports?finding=…}) auf ein Finding, das nicht mehr verknuepft ist. */
    private void clearAuditReference(String findingId, String todoId) {
        todoRepository.findById(todoId).ifPresent(todo -> {
            if (ManagedTodoWriter.REFERENCE_TYPE.equals(todo.getReferenceType())
                    && findingId.equals(todo.getReferenceId())) {
                todo.setReferenceType(null);
                todo.setReferenceId(null);
                todoRepository.save(todo);
            }
        });
    }

    @Transactional
    public void markAwaitingVerification(String todoId) {
        for (AuditFindingTodo link : findingTodoRepository.findByTodoId(todoId)) {
            if (!link.isManaged()) continue;
            findingRepository.findById(link.getFindingId()).ifPresent(finding -> {
                if (finding.getStatus() == AuditFindingStatus.OPEN) {
                    finding.setStatus(AuditFindingStatus.AWAITING_VERIFICATION);
                    findingRepository.save(finding);
                }
            });
        }
    }

    private AuditSummary toSummary(AuditReport latest, AuditReport previous, AuditReport latestAttempt,
                                   List<AuditFinding> findings) {
        Map<String, Long> byStatus = findings.stream().collect(Collectors.groupingBy(
                f -> f.getStatus().name(), Collectors.counting()));
        Map<String, Long> bySeverity = findings.stream()
                .filter(f -> !f.getStatus().isTerminal())
                .collect(Collectors.groupingBy(f -> f.getSeverity().name(), Collectors.counting()));
        return new AuditSummary(latest.getProjectId(), latest.getAuditKey(), latest.getCategory(), latest.getTitle(),
                latest.getWorkflowId(), latest.getWorkflowRunId(), latest.getId(), latest.getGateStatus(),
                latest.getGateDescription(), latest.getGeneratedAt(), latestAttempt.getIngestionStatus(),
                latestAttempt.getIngestionError(), trend(previous != null ? previous.getGateStatus() : null, latest.getGateStatus()),
                Duration.between(latest.getGeneratedAt(), Instant.now()).toDays() > 30, byStatus, bySeverity);
    }

    private AuditReportResponse toReport(AuditReport report) {
        List<AuditFindingOccurrence> occurrences = occurrenceRepository.findByReportId(report.getId());
        return new AuditReportResponse(report.getId(), report.getProjectId(), report.getWorkflowId(), report.getWorkflowRunId(),
                report.getAuditKey(), report.getCategory(), report.getTitle(), report.getMarkdownContent(), report.getGateStatus(),
                report.getGateDescription(), report.getProvider(), report.getModel(), report.getWorkflowVersion(),
                report.getIngestionStatus(), report.getIngestionError(), report.getGeneratedAt(), occurrences.size());
    }

    private Map<String, List<TodoLink>> todoLinks(List<String> findingIds) {
        if (findingIds.isEmpty()) return Map.of();
        List<AuditFindingTodo> links = findingTodoRepository.findByFindingIdIn(findingIds);
        Map<String, Todo> todos = todoRepository.findAllById(links.stream().map(AuditFindingTodo::getTodoId).toList())
                .stream().collect(Collectors.toMap(Todo::getId, Function.identity()));
        return links.stream().filter(link -> todos.containsKey(link.getTodoId())).collect(Collectors.groupingBy(
                AuditFindingTodo::getFindingId, Collectors.mapping(link -> {
                    Todo todo = todos.get(link.getTodoId());
                    return new TodoLink(todo.getId(), todo.getTitle(), todo.getStatus(), link.isManaged());
                }, Collectors.toList())));
    }

    private AuditFindingResponse toFinding(AuditFinding finding, List<TodoLink> links) {
        return new AuditFindingResponse(finding.getId(), finding.getProjectId(), finding.getAuditKey(), finding.getFingerprint(),
                finding.getRuleKey(), finding.getSubject(), finding.getSeverity(), finding.getTitle(), finding.getDescription(),
                finding.getEvidence(), TodoMapper.jsonToList(finding.getTags()), finding.getStatus(), finding.getFirstSeenRunId(),
                finding.getLastSeenRunId(), finding.getFirstSeenAt(), finding.getLastSeenAt(), finding.getOccurrenceCount(),
                finding.getRegressionCount(), finding.getResolvedAt(), finding.getDispositionNote(),
                finding.getDispositionEvidence(), finding.getDispositionAt(), finding.isUnknownRuleKey(),
                finding.isUnverifiedEvidence(), links);
    }

    private AuditFinding requireFinding(String id) {
        return findingRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));
    }

    private static String managedTodoDescription(AuditFinding finding) {
        return (finding.getDescription() != null ? finding.getDescription() : "Audit finding requires remediation.")
                + "\n\nEvidence: " + (finding.getEvidence() != null ? finding.getEvidence() : "not provided")
                + "\n\n[Audit finding](/reports?finding=" + finding.getId() + ")"
                + " · [Evidence run](/runs/" + finding.getLastSeenRunId() + ")";
    }

    private static String trend(GateStatus previous, GateStatus current) {
        if (previous == null || current == null) return "NEW";
        if (current.ordinal() < previous.ordinal()) return "IMPROVED";
        if (current.ordinal() > previous.ordinal()) return "REGRESSED";
        return "UNCHANGED";
    }

    public record AuditSummary(String projectId, String auditKey, String category, String title, String workflowId,
                               String latestRunId, String latestReportId, GateStatus gateStatus, String gateDescription,
                               Instant generatedAt, AuditIngestionStatus latestAttemptStatus, String latestAttemptError,
                               String trend, boolean stale, Map<String, Long> findingsByStatus,
                               Map<String, Long> openFindingsBySeverity) {}
    public record AuditDetail(AuditSummary summary, AuditReportResponse latestReport) {}
    public record AuditReportResponse(String id, String projectId, String workflowId, String workflowRunId,
                                      String auditKey, String category, String title, String markdownContent,
                                      GateStatus gateStatus, String gateDescription, String provider, String model,
                                      String workflowVersion, AuditIngestionStatus ingestionStatus,
                                      String ingestionError, Instant generatedAt, int findingCount) {}
    public record AuditFindingResponse(String id, String projectId, String auditKey, String fingerprint,
                                       String ruleKey, String subject, AuditSeverity severity, String title,
                                       String description, String evidence, List<String> tags, AuditFindingStatus status,
                                       String firstSeenRunId, String lastSeenRunId, Instant firstSeenAt, Instant lastSeenAt,
                                       int occurrenceCount, int regressionCount, Instant resolvedAt,
                                       String dispositionNote, String dispositionEvidence, Instant dispositionAt,
                                       boolean unknownRuleKey, boolean unverifiedEvidence,
                                       List<TodoLink> todos) {}
    public record TodoLink(String id, String title, Todo.Status status, boolean managed) {}
}
