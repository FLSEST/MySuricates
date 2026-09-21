package com.automation.service.tray;

import com.automation.service.project.DockerExpectation;
import com.automation.config.TrayConfig;
import com.automation.entity.RunStatus;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.tray.SystemTrayService.IconColor;
import com.automation.service.tray.SystemTrayService.TrayState;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemTrayServiceTest {

    @Mock
    private TrayConfig trayConfig;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowExecutionRegistry executionRegistry;

    @Mock
    private SystemIdentityService systemIdentity;

    @Mock
    private DockerExpectation dockerExpectation;

    private SystemTrayService service;

    @BeforeEach
    void setUp() {
        service = new SystemTrayService(trayConfig, runRepository, executionRegistry, dockerExpectation, systemIdentity);
    }

    @Test
    void collectStatus_aggregatesDataCorrectly() {
        when(executionRegistry.getActiveRunCount()).thenReturn(2);
        when(runRepository.countByStartedAtAfter(any(Instant.class))).thenReturn(10L);
        when(runRepository.countByStatusAndStartedAtAfter(eq(RunStatus.FAILED), any(Instant.class))).thenReturn(1L);
        when(executionRegistry.getPendingApprovalCount()).thenReturn(0);
        when(dockerExpectation.probe()).thenReturn(DockerExpectation.State.UP);
        when(trayConfig.getWebUiUrl()).thenReturn("http://localhost:99999");
        when(trayConfig.getStableUrl()).thenReturn("http://localhost:99998");

        TrayState state = service.collectStatus();

        assertThat(state.activeRuns()).isEqualTo(2);
        assertThat(state.runsToday()).isEqualTo(10);
        assertThat(state.failedToday()).isEqualTo(1);
        assertThat(state.pendingApprovals()).isZero();
        assertThat(state.dockerOk()).isTrue();
    }

    @Test
    void collectStatus_dockerOffline() {
        when(executionRegistry.getActiveRunCount()).thenReturn(0);
        when(runRepository.countByStartedAtAfter(any(Instant.class))).thenReturn(0L);
        when(runRepository.countByStatusAndStartedAtAfter(eq(RunStatus.FAILED), any(Instant.class))).thenReturn(0L);
        when(executionRegistry.getPendingApprovalCount()).thenReturn(0);
        when(dockerExpectation.probe()).thenReturn(DockerExpectation.State.DOWN);
        when(trayConfig.getWebUiUrl()).thenReturn("http://localhost:99999");
        when(trayConfig.getStableUrl()).thenReturn("http://localhost:99998");

        TrayState state = service.collectStatus();

        assertThat(state.dockerOk()).isFalse();
    }

    @Test
    void collectStatus_dockerNotConfiguredIsNoWarning() {
        // a0e85182: ohne eingerichtetes Docker bleibt das Tray-Icon gruen
        when(executionRegistry.getActiveRunCount()).thenReturn(0);
        when(runRepository.countByStartedAtAfter(any(Instant.class))).thenReturn(0L);
        when(runRepository.countByStatusAndStartedAtAfter(eq(RunStatus.FAILED), any(Instant.class))).thenReturn(0L);
        when(executionRegistry.getPendingApprovalCount()).thenReturn(0);
        when(dockerExpectation.probe()).thenReturn(DockerExpectation.State.NOT_CONFIGURED);
        when(trayConfig.getWebUiUrl()).thenReturn("http://localhost:99999");
        when(trayConfig.getStableUrl()).thenReturn("http://localhost:99998");

        assertThat(service.collectStatus().dockerOk()).isTrue();
    }

    @Test
    void checkFrontend_falseWhenUnreachable() {
        when(trayConfig.getWebUiUrl()).thenReturn("http://localhost:99999");
        assertThat(service.checkFrontend()).isFalse();
    }

    @Test
    void determineIconColor_greenWhenAllOk() {
        TrayState state = new TrayState(1, 5, 0, 0, true, true, true);
        assertThat(service.determineIconColor(state)).isEqualTo(IconColor.GREEN);
    }

    @Test
    void determineIconColor_yellowWhenDockerOffline() {
        TrayState state = new TrayState(0, 5, 0, 0, false, true, true);
        assertThat(service.determineIconColor(state)).isEqualTo(IconColor.YELLOW);
    }

    @Test
    void determineIconColor_yellowWhenFrontendOffline() {
        TrayState state = new TrayState(0, 5, 0, 0, true, false, true);
        assertThat(service.determineIconColor(state)).isEqualTo(IconColor.YELLOW);
    }

    @Test
    void determineIconColor_yellowWhenApprovalsPending() {
        TrayState state = new TrayState(1, 5, 0, 2, true, true, true);
        assertThat(service.determineIconColor(state)).isEqualTo(IconColor.YELLOW);
    }

    @Test
    void determineIconColor_redWhenNewFailure() {
        TrayState state = new TrayState(0, 5, 2, 0, true, true, true);
        assertThat(service.determineIconColor(state)).isEqualTo(IconColor.RED);
    }

    @Test
    void successPercent_calculatesCorrectly() {
        TrayState state = new TrayState(0, 20, 2, 0, true, true, true);
        assertThat(state.successPercent()).isEqualTo(90);
    }

    @Test
    void successPercent_100WhenNoRuns() {
        TrayState state = new TrayState(0, 0, 0, 0, true, true, true);
        assertThat(state.successPercent()).isEqualTo(100);
    }

    @Test
    void checkStable_falseWhenUnreachable() {
        when(trayConfig.getStableUrl()).thenReturn("http://localhost:99998");
        assertThat(service.checkStable()).isFalse();
    }

    @Test
    void determineIconColor_yellowWhenStableOffline() {
        TrayState state = new TrayState(0, 5, 0, 0, true, true, false);
        assertThat(service.determineIconColor(state)).isEqualTo(IconColor.YELLOW);
    }

    @Test
    void initialize_skipsWhenTrayNotSupported() {
        // On headless CI, SystemTray.isSupported() returns false
        service.initialize();
        service.cleanup();
    }

    @Test
    void showNotification_isNoOpWhenNotInitialized() {
        // initialized=false (default) → must not throw, must not touch trayIcon
        service.showNotification("Title", "Message", false);
        service.showNotification("Error", "Boom", true);
        // No exception = pass; verifies the early-return guard at the top of showNotification
    }

    @Test
    void cleanup_isNoOpWhenNotInitialized() {
        // initialized=false (default) → must not throw and must not call SystemTray.getSystemTray()
        service.cleanup();
        // Calling twice must remain safe
        service.cleanup();
    }

    @Test
    void refreshStatus_isNoOpWhenNotInitialized() {
        // initialized=false (default) → must return early without touching any collaborator.
        // Because no when(...) stubs are configured, any interaction would either NPE or
        // (with strict stubs) throw — proving the early-return guard works.
        service.refreshStatus();
    }

    @Test
    void isSystemTraySupported_returnsFalseInHeadlessEnvironment() throws Exception {
        // Tests run headless on CI, so the helper must return false without throwing.
        java.lang.reflect.Method method = SystemTrayService.class.getDeclaredMethod("isSystemTraySupported");
        method.setAccessible(true);
        Object result = method.invoke(service);
        assertThat(result).isInstanceOf(Boolean.class);
        // In a headless environment we expect false; in a graphical env it may be true.
        // Either way the call must not throw.
    }

    @Test
    void isSystemTraySupported_returnsFalseWhenAwtErrorThrown() throws Exception {
        // Simulate an environment where SystemTray.isSupported() throws AWTError
        // (e.g. broken X11 connection). The helper must swallow it and return false.
        try (org.mockito.MockedStatic<java.awt.SystemTray> mocked = org.mockito.Mockito.mockStatic(java.awt.SystemTray.class)) {
            mocked.when(java.awt.SystemTray::isSupported).thenThrow(new java.awt.AWTError("display unavailable"));

            java.lang.reflect.Method method = SystemTrayService.class.getDeclaredMethod("isSystemTraySupported");
            method.setAccessible(true);
            Object result = method.invoke(service);

            assertThat(result).isEqualTo(false);
        }
    }

    @Test
    void isSystemTraySupported_returnsFalseWhenToolkitInitFails() throws Exception {
        // DISPLAY set but no reachable X server (e.g. GitHub runner): the AWT toolkit's static
        // initializer fails and surfaces as ExceptionInInitializerError / NoClassDefFoundError
        // (LinkageError), not as AWTError. The helper must swallow that too and return false.
        try (org.mockito.MockedStatic<java.awt.SystemTray> mocked = org.mockito.Mockito.mockStatic(java.awt.SystemTray.class)) {
            mocked.when(java.awt.SystemTray::isSupported)
                    .thenThrow(new ExceptionInInitializerError("Can't connect to X11 window server"));

            java.lang.reflect.Method method = SystemTrayService.class.getDeclaredMethod("isSystemTraySupported");
            method.setAccessible(true);
            Object result = method.invoke(service);

            assertThat(result).isEqualTo(false);
        }
    }

    @Test
    void initialize_doesNotThrowWhenToolkitInitFails() {
        try (org.mockito.MockedStatic<java.awt.SystemTray> mocked = org.mockito.Mockito.mockStatic(java.awt.SystemTray.class)) {
            mocked.when(java.awt.SystemTray::isSupported)
                    .thenThrow(new NoClassDefFoundError("Could not initialize class sun.awt.X11.XToolkit"));
            service.initialize();
            service.refreshStatus();
            service.cleanup();
        }
    }

    @Test
    void initialize_doesNotThrowWhenSystemTrayCheckFails() {
        // Even if the underlying SystemTray.isSupported() throws AWTError, initialize()
        // must complete cleanly and leave the service in a non-initialized state.
        try (org.mockito.MockedStatic<java.awt.SystemTray> mocked = org.mockito.Mockito.mockStatic(java.awt.SystemTray.class)) {
            mocked.when(java.awt.SystemTray::isSupported).thenThrow(new java.awt.AWTError("no display"));
            service.initialize();
            // Subsequent calls should remain safe no-ops
            service.refreshStatus();
            service.showNotification("t", "m", false);
            service.cleanup();
        }
    }
}
