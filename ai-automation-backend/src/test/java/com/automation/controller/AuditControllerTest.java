package com.automation.controller;

import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;
import com.automation.service.audit.AuditCenterService;
import com.automation.dto.response.PageResponse;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.GateStatus;
import com.automation.entity.TriggerSource;
import com.automation.service.audit.AuditCenterService.AuditFindingResponse;
import com.automation.service.audit.AuditCenterService.AuditReportResponse;
import com.automation.service.audit.AuditCenterService.AuditSummary;
import com.automation.service.audit.AuditCenterService.TodoLink;
import com.automation.entity.Todo;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AuditControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AuditCenterService auditCenterService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;
    @MockitoBean private ActivityLogService activityLogService;

    @Test
    void getAudit_dottedKey_isPassedThroughUntruncated() throws Exception {
        // Regression for TODO a5acdac0: every audit key contains a dot (mysuricates.health).
        // The detail endpoint must receive the full key, not a suffix-stripped one.
        mockMvc.perform(get("/api/v1/audits/mysuricates.health").param("projectId", "proj-1"))
                .andExpect(status().isOk());

        verify(auditCenterService).getAudit("proj-1", "mysuricates.health");
    }

    @Test
    void getAudit_missingProjectId_returns400NotInternalError() throws Exception {
        mockMvc.perform(get("/api/v1/audits/mysuricates.health"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error").value("Missing required parameter: projectId"));
    }

    @Test
    void getAudit_unknownKey_returns404() throws Exception {
        when(auditCenterService.getAudit(anyString(), anyString()))
                .thenThrow(new ResourceNotFoundException("Audit", "plugin.self-improvement"));

        mockMvc.perform(get("/api/v1/audits/plugin.self-improvement").param("projectId", "proj-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listFindings_invalidEnumValue_returns400() throws Exception {
        // severity is an enum @RequestParam — a bogus value used to surface as 500 INTERNAL_ERROR.
        mockMvc.perform(get("/api/v1/audit-findings").param("severity", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void updateFinding_acceptedRisk_exposesDispositionSnapshotAndTimestamp() throws Exception {
        Instant acceptedAt = Instant.parse("2026-09-13T10:00:00Z");
        when(auditCenterService.updateFinding(eq("f1"), eq(AuditFindingStatus.ACCEPTED_RISK), eq("Split geplant")))
                .thenReturn(new AuditFindingResponse("f1", "proj-1", "mysuricates.health", "fp", "size.class", "Big.java",
                        AuditSeverity.HIGH, "Big.java ist zu gross", null, "Big.java: 320 LOC", List.of(),
                        AuditFindingStatus.ACCEPTED_RISK, "run-0", "run-1", acceptedAt, acceptedAt, 2, 0, acceptedAt,
                        "Split geplant", "Big.java: 320 LOC", acceptedAt, false, false, List.of()));

        mockMvc.perform(patch("/api/v1/audit-findings/f1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED_RISK\",\"dispositionNote\":\"Split geplant\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED_RISK"))
                .andExpect(jsonPath("$.dispositionNote").value("Split geplant"))
                .andExpect(jsonPath("$.dispositionEvidence").value("Big.java: 320 LOC"))
                .andExpect(jsonPath("$.dispositionAt").value("2026-09-13T10:00:00Z"));
    }

    @Test
    void listAudits_passesOptionalFilters_andSerialisesTheSummary() throws Exception {
        Instant at = Instant.parse("2026-09-15T10:00:00Z");
        when(auditCenterService.listAudits("proj-1", "security", GateStatus.RED)).thenReturn(List.of(
                new AuditSummary("proj-1", "mysuricates.security", "security", "Security", "wf-1", "run-1", "r-1",
                        GateStatus.RED, "2 kritisch", at, AuditIngestionStatus.VALID, null, "REGRESSED", false,
                        Map.of("OPEN", 2L), Map.of("CRITICAL", 2L))));

        mockMvc.perform(get("/api/v1/audits").param("projectId", "proj-1").param("category", "security")
                        .param("gateStatus", "RED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].auditKey").value("mysuricates.security"))
                .andExpect(jsonPath("$[0].trend").value("REGRESSED"))
                .andExpect(jsonPath("$[0].openFindingsBySeverity.CRITICAL").value(2));
    }

    @Test
    void listAudits_withoutFilters_passesNulls() throws Exception {
        mockMvc.perform(get("/api/v1/audits")).andExpect(status().isOk());

        verify(auditCenterService).listAudits(null, null, null);
    }

    @Test
    void listReports_defaultsToFirstPageOfTwenty() throws Exception {
        when(auditCenterService.listReports("proj-1", "mysuricates.health", 0, 20))
                .thenReturn(new PageResponse<>(List.of(report("r-1")), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/audits/mysuricates.health/reports").param("projectId", "proj-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("r-1"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listReports_passesExplicitPaging() throws Exception {
        mockMvc.perform(get("/api/v1/audits/mysuricates.health/reports").param("projectId", "proj-1")
                        .param("page", "2").param("size", "5"))
                .andExpect(status().isOk());

        verify(auditCenterService).listReports("proj-1", "mysuricates.health", 2, 5);
    }

    @Test
    void getReport_returnsTheReport_or404() throws Exception {
        when(auditCenterService.getReport("r-1")).thenReturn(report("r-1"));
        when(auditCenterService.getReport("r-missing")).thenThrow(new ResourceNotFoundException("AuditReport", "r-missing"));

        mockMvc.perform(get("/api/v1/audit-reports/r-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findingCount").value(3))
                .andExpect(jsonPath("$.gateStatus").value("GREEN"));
        mockMvc.perform(get("/api/v1/audit-reports/r-missing"))
                .andExpect(status().isNotFound());
    }

    /** Herkunft des Laufs im Report (TODO a3b89355): jedes Feld steht im JSON, unbekannt als null. */
    @Test
    void getReport_serialisesTheRunOrigin_unknownFieldsAsNull() throws Exception {
        when(auditCenterService.getReport("r-chain")).thenReturn(new AuditReportResponse("r-chain", "proj-1", "wf-1",
                "run-1", "mysuricates.health", "health", "Health", "# Health", GateStatus.GREEN, null, "CLAUDE", "opus",
                "4.4.0", AuditIngestionStatus.VALID, null, Instant.parse("2026-09-15T10:00:00Z"), 3,
                TriggerSource.SCHEDULE, "sched-1", "cr-1", "chain-1", "Wochen-Audits", 12));
        when(auditCenterService.getReport("r-old")).thenReturn(report("r-old"));

        mockMvc.perform(get("/api/v1/audit-reports/r-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowVersion").value("4.4.0"))
                .andExpect(jsonPath("$.triggerSource").value("SCHEDULE"))
                .andExpect(jsonPath("$.scheduleId").value("sched-1"))
                .andExpect(jsonPath("$.chainRunId").value("cr-1"))
                .andExpect(jsonPath("$.chainId").value("chain-1"))
                .andExpect(jsonPath("$.chainName").value("Wochen-Audits"))
                .andExpect(jsonPath("$.definitionVersionNumber").value(12));
        mockMvc.perform(get("/api/v1/audit-reports/r-old"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerSource").value(nullValue()))
                .andExpect(jsonPath("$.scheduleId").value(nullValue()))
                .andExpect(jsonPath("$.chainRunId").value(nullValue()))
                .andExpect(jsonPath("$.chainId").value(nullValue()))
                .andExpect(jsonPath("$.chainName").value(nullValue()))
                .andExpect(jsonPath("$.definitionVersionNumber").value(nullValue()));
    }

    @Test
    void listFindings_defaultsToFiftyAndPassesEnumFilters() throws Exception {
        when(auditCenterService.listFindings(0, 50, "proj-1", "mysuricates.health", AuditFindingStatus.OPEN,
                AuditSeverity.HIGH)).thenReturn(new PageResponse<>(List.of(finding("f1", List.of())), 0, 50, 1, 1));

        mockMvc.perform(get("/api/v1/audit-findings").param("projectId", "proj-1")
                        .param("auditKey", "mysuricates.health").param("status", "OPEN").param("severity", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("f1"));
    }

    @Test
    void getFinding_serialisesTodoLinks() throws Exception {
        when(auditCenterService.getFinding("f1")).thenReturn(finding("f1",
                List.of(new TodoLink("t1", "Fix", Todo.Status.OPEN, true))));

        mockMvc.perform(get("/api/v1/audit-findings/f1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todos[0].id").value("t1"))
                .andExpect(jsonPath("$.todos[0].managed").value(true));
    }

    @Test
    void createManagedTodo_postsToTheFinding() throws Exception {
        when(auditCenterService.createManagedTodo("f1")).thenReturn(finding("f1",
                List.of(new TodoLink("t1", "Fix", Todo.Status.OPEN, true))));

        mockMvc.perform(post("/api/v1/audit-findings/f1/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todos[0].managed").value(true));
    }

    @Test
    void linkTodo_readsTheTodoIdFromTheBody() throws Exception {
        when(auditCenterService.linkTodo("f1", "t2")).thenReturn(finding("f1",
                List.of(new TodoLink("t2", "Manuell", Todo.Status.IN_PROGRESS, false))));

        mockMvc.perform(post("/api/v1/audit-findings/f1/todo-links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"todoId\":\"t2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todos[0].managed").value(false));
    }

    @Test
    void linkTodo_foreignProject_returns400() throws Exception {
        when(auditCenterService.linkTodo("f1", "t-foreign"))
                .thenThrow(new IllegalArgumentException("Finding and TODO must belong to the same project"));

        mockMvc.perform(post("/api/v1/audit-findings/f1/todo-links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"todoId\":\"t-foreign\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void unlinkTodo_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/audit-findings/f1/todo-links/t1"))
                .andExpect(status().isNoContent());

        verify(auditCenterService).unlinkTodo("f1", "t1");
    }

    private static AuditReportResponse report(String id) {
        return new AuditReportResponse(id, "proj-1", "wf-1", "run-1", "mysuricates.health", "health", "Health",
                "# Health", GateStatus.GREEN, null, "CLAUDE", "opus", "3", AuditIngestionStatus.VALID, null,
                Instant.parse("2026-09-15T10:00:00Z"), 3, null, null, null, null, null, null);
    }

    private static AuditFindingResponse finding(String id, List<TodoLink> todos) {
        Instant at = Instant.parse("2026-09-13T10:00:00Z");
        return new AuditFindingResponse(id, "proj-1", "mysuricates.health", "fp", "size.class", "Big.java",
                AuditSeverity.HIGH, "Big.java ist zu gross", null, "Big.java: 320 LOC", List.of(),
                AuditFindingStatus.OPEN, "run-0", "run-1", at, at, 1, 0, null, null, null, null, false, false, todos);
    }
}
