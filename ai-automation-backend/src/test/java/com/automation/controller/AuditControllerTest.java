package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.audit.AuditCenterService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
