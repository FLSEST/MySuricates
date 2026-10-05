package com.automation.controller;

import com.automation.dto.response.PageResponse;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;
import com.automation.entity.GateStatus;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.audit.AuditCenterService;
import com.automation.service.audit.AuditCenterService.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Audit center: results of audit workflows (gate status per report), the findings derived from them and their TODOs. */
@RequiresModule(ModuleCatalog.AUDIT)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuditController {
    private final AuditCenterService auditCenterService;

    /**
     * Lists every audit (per project and {@code auditKey}) with its latest valid report and finding counts.
     * Optional filters: {@code projectId}, {@code category}, {@code gateStatus}.
     */
    @GetMapping("/audits")
    public List<AuditSummary> listAudits(@RequestParam(required = false) String projectId,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) GateStatus gateStatus) {
        return auditCenterService.listAudits(projectId, category, gateStatus);
    }

    /**
     * Returns one audit: its summary with finding counts and its latest valid report including the text.
     * {@code projectId} is required; 404 if the audit has no valid report.
     */
    @GetMapping("/audits/{auditKey}")
    public AuditDetail getAudit(@PathVariable String auditKey, @RequestParam String projectId) {
        return auditCenterService.getAudit(projectId, auditKey);
    }

    /**
     * Lists the reports of an audit page by page, newest first; {@code projectId} is required.
     * Heads only: {@code markdownContent} is {@code null}, the text comes from {@code GET /api/v1/audit-reports/{id}}.
     */
    @GetMapping("/audits/{auditKey}/reports")
    public PageResponse<AuditReportResponse> listReports(@PathVariable String auditKey,
                                                         @RequestParam String projectId,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return auditCenterService.listReports(projectId, auditKey, page, size);
    }

    /** Returns one audit report including its Markdown text. */
    @GetMapping("/audit-reports/{id}")
    public AuditReportResponse getReport(@PathVariable String id) {
        return auditCenterService.getReport(id);
    }

    /**
     * Lists findings across all audits page by page, most severe first, then most recently seen.
     * Optional filters: {@code projectId}, {@code auditKey}, {@code status}, {@code severity}.
     */
    @GetMapping("/audit-findings")
    public PageResponse<AuditFindingResponse> listFindings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String auditKey,
            @RequestParam(required = false) AuditFindingStatus status,
            @RequestParam(required = false) AuditSeverity severity) {
        return auditCenterService.listFindings(page, size, projectId, auditKey, status, severity);
    }

    /** Returns one finding with its linked TODOs. */
    @GetMapping("/audit-findings/{id}")
    public AuditFindingResponse getFinding(@PathVariable String id) {
        return auditCenterService.getFinding(id);
    }

    /**
     * Changes a finding's status and/or disposition note; fields left out stay unchanged.
     * A terminal status sets the resolution time, and the managed TODO is reconciled with the new state.
     */
    @PatchMapping("/audit-findings/{id}")
    public AuditFindingResponse updateFinding(@PathVariable String id, @RequestBody UpdateFindingRequest request) {
        return auditCenterService.updateFinding(id, request.status(), request.dispositionNote());
    }

    /** Creates a TODO managed by MySuricates from the finding and returns the finding with its TODO links. */
    @PostMapping("/audit-findings/{id}/todos")
    public AuditFindingResponse createManagedTodo(@PathVariable String id) {
        return auditCenterService.createManagedTodo(id);
    }

    /** Links an existing TODO ({@code todoId}) to the finding. */
    @PostMapping("/audit-findings/{id}/todo-links")
    public AuditFindingResponse linkTodo(@PathVariable String id, @RequestBody LinkTodoRequest request) {
        return auditCenterService.linkTodo(id, request.todoId());
    }

    /** Removes the link between a finding and a TODO; answers 204. */
    @DeleteMapping("/audit-findings/{id}/todo-links/{todoId}")
    public ResponseEntity<Void> unlinkTodo(@PathVariable String id, @PathVariable String todoId) {
        auditCenterService.unlinkTodo(id, todoId);
        return ResponseEntity.noContent().build();
    }

    public record UpdateFindingRequest(AuditFindingStatus status, String dispositionNote) {}
    public record LinkTodoRequest(String todoId) {}
}
