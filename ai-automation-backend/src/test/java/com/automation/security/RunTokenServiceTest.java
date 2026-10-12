package com.automation.security;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.util.RunAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunTokenServiceTest {

    private static final Map<String, Object> READ = Map.of("permissions", List.of("read"));

    @Mock private ModuleRegistry modules;
    @Mock private ActivityLogService activityLog;

    private RunTokenService service;

    @BeforeEach
    void setUp() {
        service = new RunTokenService(modules, activityLog);
        lenient().when(modules.isEnabled(ModuleCatalog.RUN_TOKENS)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        service.revoke("run-1", "wf-1");
        service.revoke("run-2", "wf-1");
    }

    @Test
    @DisplayName("only metadata.permissions with \"read\" requests a token")
    void requestedOnlyWithReadPermission() {
        assertThat(RunTokenService.requested(READ)).isTrue();
        assertThat(RunTokenService.requested(Map.of("permissions", "read"))).isTrue();
        assertThat(RunTokenService.requested(Map.of("permissions", List.of("write")))).isFalse();
        assertThat(RunTokenService.requested(Map.of())).isFalse();
        assertThat(RunTokenService.requested(null)).isFalse();
    }

    @Test
    @DisplayName("a requested token is random, resolves to its run and is masked from now on")
    void issuesAResolvableToken() {
        String first = service.issue("run-1", READ).orElseThrow();
        String second = service.issue("run-2", READ).orElseThrow();

        assertThat(first).hasSizeGreaterThanOrEqualTo(43).isNotEqualTo(second);
        assertThat(service.resolve(first)).contains("run-1");
        assertThat(service.resolve("guess")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
        assertThat(RunAccess.mask(first)).isEqualTo(RunAccess.MASK);
    }

    @Test
    @DisplayName("no token without the permission or with the module switched off")
    void noTokenWhenNotRequestedOrDisabled() {
        assertThat(service.issue("run-1", Map.of())).isEmpty();

        when(modules.isEnabled(ModuleCatalog.RUN_TOKENS)).thenReturn(false);
        assertThat(service.issue("run-1", READ)).isEmpty();
    }

    @Test
    @DisplayName("revoking ends the token and logs the reads of the run once")
    void revokeEndsTheTokenAndLogsReads() {
        String token = service.issue("run-1", READ).orElseThrow();
        service.recordRead(token, "/api/v1/todos");
        service.recordRead(token, "/api/v1/todos/abc");
        service.recordRead(token, "/api/v1/workflows");

        service.revoke("run-1", "wf-1");

        assertThat(service.resolve(token)).isEmpty();
        assertThat(RunAccess.mask(token)).isEqualTo(token);
        verify(activityLog).info(eq(LogCategory.SECURITY), contains("3 read(s) (todos 2, workflows 1)"),
                eq("RunTokenService"), eq("run-1"), eq("wf-1"));
    }

    @Test
    @DisplayName("a token without reads ends silently, a second revoke is harmless")
    void unusedTokenIsNotLogged() {
        service.issue("run-1", READ);

        service.revoke("run-1", "wf-1");
        service.revoke("run-1", "wf-1");

        verify(activityLog, never()).info(any(LogCategory.class), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a resumed run gets a fresh token, the old one stops working")
    void reissueReplacesTheOldToken() {
        String old = service.issue("run-1", READ).orElseThrow();
        String fresh = service.issue("run-1", READ).orElseThrow();

        assertThat(service.resolve(old)).isEmpty();
        assertThat(service.resolve(fresh)).contains("run-1");
    }
}
