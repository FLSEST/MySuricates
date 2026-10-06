package com.automation.controller;

import com.automation.entity.Workflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.AnalyzerFinding;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowAnalyzerController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowAnalyzerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowService workflowService;
    @MockitoBean
    private WorkflowAnalyzer analyzer;
    @MockitoBean
    private ActivityLogService activityLogService;
    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private static Workflow wf(String id, String name) {
        return Workflow.builder().id(id).name(name).build();
    }

    private static AnalyzerFinding finding(String rule, Severity severity) {
        return new AnalyzerFinding(rule, severity, 0, "b", "detail " + rule);
    }

    @Test
    void analyzeLiefertBefundeMitZaehlungJeSchwere() throws Exception {
        Workflow w = wf("wf-1", "Bericht");
        when(workflowService.findByIdEager("wf-1")).thenReturn(w);
        when(analyzer.analyze(w)).thenReturn(List.of(
                finding("unknown-block-reference", Severity.HIGH),
                finding("missing-strict-mode", Severity.LOW),
                finding("dated-model-id", Severity.LOW)));

        mockMvc.perform(get("/api/v1/workflows/wf-1/analyze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.workflowName").value("Bericht"))
                .andExpect(jsonPath("$.findingCount").value(3))
                .andExpect(jsonPath("$.bySeverity.HIGH").value(1))
                .andExpect(jsonPath("$.bySeverity.LOW").value(2))
                .andExpect(jsonPath("$.bySeverity.MEDIUM").doesNotExist())
                .andExpect(jsonPath("$.findings[0].rule").value("unknown-block-reference"))
                .andExpect(jsonPath("$.findings[0].severity").value("HIGH"));
    }

    /** TODO 81773d7b: every finding carries its text in German ({@code detail}, as before) and in English ({@code detailEn}). */
    @Test
    void analyzeLiefertDetailDeutschUndEnglisch() throws Exception {
        Workflow w = wf("wf-3", "Zweisprachig");
        when(workflowService.findByIdEager("wf-3")).thenReturn(w);
        when(analyzer.analyze(w)).thenReturn(List.of(new AnalyzerFinding("missing-required-config-key", Severity.HIGH,
                0, "b", "Pflichtschlüssel command fehlt", "required key command is missing")));

        mockMvc.perform(get("/api/v1/workflows/wf-3/analyze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings[0].detail").value("Pflichtschlüssel command fehlt"))
                .andExpect(jsonPath("$.findings[0].detailEn").value("required key command is missing"));
    }

    @Test
    void analyzeOhneBefundeLiefertLeereZaehlung() throws Exception {
        Workflow w = wf("wf-2", "Sauber");
        when(workflowService.findByIdEager("wf-2")).thenReturn(w);
        when(analyzer.analyze(w)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/workflows/wf-2/analyze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findingCount").value(0))
                .andExpect(jsonPath("$.bySeverity").isEmpty())
                .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    void analyzeUnbekannterWorkflowIst404() throws Exception {
        when(workflowService.findByIdEager("nope")).thenThrow(new ResourceNotFoundException("Workflow", "nope"));

        mockMvc.perform(get("/api/v1/workflows/nope/analyze"))
                .andExpect(status().isNotFound());
    }

    @Test
    void bestandslaufVerdichtetUndSortiertNachAnzahl() throws Exception {
        Workflow sauber = wf("a", "Sauber");
        Workflow wenig = wf("b", "Wenig");
        Workflow viel = wf("c", "Viel");
        when(workflowService.findAll()).thenReturn(List.of(sauber, wenig, viel));
        when(analyzer.analyze(sauber)).thenReturn(List.of());
        when(analyzer.analyze(wenig)).thenReturn(List.of(finding("unwired-output", Severity.MEDIUM)));
        when(analyzer.analyze(viel)).thenReturn(List.of(
                finding("unwired-output", Severity.MEDIUM),
                finding("absolute-home-path", Severity.HIGH),
                finding("missing-strict-mode", Severity.LOW)));

        mockMvc.perform(get("/api/v1/workflows/analyze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsChecked").value(3))
                .andExpect(jsonPath("$.workflowsAffected").value(2))
                .andExpect(jsonPath("$.findingsTotal").value(4))
                .andExpect(jsonPath("$.byRule.unwired-output").value(2))
                .andExpect(jsonPath("$.byRule.absolute-home-path").value(1))
                .andExpect(jsonPath("$.workflows[*].workflowId", contains("c", "b")))
                .andExpect(jsonPath("$.workflows[0].findings").value(3))
                .andExpect(jsonPath("$.workflows[0].bySeverity.HIGH").value(1));
    }

    @Test
    void minSeverityBlendetLeichtereBefundeAus() throws Exception {
        Workflow nurLeicht = wf("a", "Leicht");
        Workflow gemischt = wf("b", "Gemischt");
        when(workflowService.findAll()).thenReturn(List.of(nurLeicht, gemischt));
        when(analyzer.analyze(nurLeicht)).thenReturn(List.of(finding("missing-strict-mode", Severity.LOW)));
        when(analyzer.analyze(gemischt)).thenReturn(List.of(
                finding("absolute-home-path", Severity.HIGH),
                finding("unwired-output", Severity.MEDIUM),
                finding("dated-model-id", Severity.LOW)));

        mockMvc.perform(get("/api/v1/workflows/analyze").param("minSeverity", "MEDIUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsChecked").value(2))
                .andExpect(jsonPath("$.workflowsAffected").value(1))
                .andExpect(jsonPath("$.findingsTotal").value(2))
                .andExpect(jsonPath("$.byRule.dated-model-id").doesNotExist())
                .andExpect(jsonPath("$.workflows[0].workflowId").value("b"));

        mockMvc.perform(get("/api/v1/workflows/analyze").param("minSeverity", "HIGH"))
                .andExpect(jsonPath("$.findingsTotal").value(1))
                .andExpect(jsonPath("$.byRule.absolute-home-path").value(1));
    }

    @Test
    void unbekannteMinSeverityIst400() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/analyze").param("minSeverity", "KRITISCH"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(analyzer);
    }
}
