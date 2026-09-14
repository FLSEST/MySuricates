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

@RequiresModule(ModuleCatalog.AUDIT)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuditController {
    private final AuditCenterService auditCenterService;

    @GetMapping("/audits")
    public List<AuditSummary> listAudits(@RequestParam(required = false) String projectId,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) GateStatus gateStatus) {
        return auditCenterService.listAudits(projectId, category, gateStatus);
    }

    @GetMapping("/audits/{auditKey}")
    public AuditDetail getAudit(@PathVariable String auditKey, @RequestParam String projectId) {
        return auditCenterService.getAudit(projectId, auditKey);
    }

    @GetMapping("/audits/{auditKey}/reports")
    public PageResponse<AuditReportResponse> listReports(@PathVariable String auditKey,
                                                         @RequestParam String projectId,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return auditCenterService.listReports(projectId, auditKey, page, size);
    }

    @GetMapping("/audit-reports/{id}")
    public AuditReportResponse getReport(@PathVariable String id) {
        return auditCenterService.getReport(id);
    }

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

    @GetMapping("/audit-findings/{id}")
    public AuditFindingResponse getFinding(@PathVariable String id) {
        return auditCenterService.getFinding(id);
    }

    @PatchMapping("/audit-findings/{id}")
    public AuditFindingResponse updateFinding(@PathVariable String id, @RequestBody UpdateFindingRequest request) {
        return auditCenterService.updateFinding(id, request.status(), request.dispositionNote());
    }

    @PostMapping("/audit-findings/{id}/todos")
    public AuditFindingResponse createManagedTodo(@PathVariable String id) {
        return auditCenterService.createManagedTodo(id);
    }

    @PostMapping("/audit-findings/{id}/todo-links")
    public AuditFindingResponse linkTodo(@PathVariable String id, @RequestBody LinkTodoRequest request) {
        return auditCenterService.linkTodo(id, request.todoId());
    }

    @DeleteMapping("/audit-findings/{id}/todo-links/{todoId}")
    public ResponseEntity<Void> unlinkTodo(@PathVariable String id, @PathVariable String todoId) {
        auditCenterService.unlinkTodo(id, todoId);
        return ResponseEntity.noContent().build();
    }

    public record UpdateFindingRequest(AuditFindingStatus status, String dispositionNote) {}
    public record LinkTodoRequest(String todoId) {}
}
