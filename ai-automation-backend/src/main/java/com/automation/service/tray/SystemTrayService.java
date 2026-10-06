package com.automation.service.tray;

import com.automation.service.project.DockerExpectation;
import com.automation.config.TrayConfig;
import com.automation.entity.RunStatus;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.tray.TrayInstanceMonitor.InstanceKind;
import com.automation.service.tray.TrayInstanceMonitor.InstanceLight;
import com.automation.service.tray.TrayInstanceMonitor.LightColor;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperty(name = "automation.tray.enabled", havingValue = "true", matchIfMissing = true)
public class SystemTrayService {

    private final TrayConfig trayConfig;
    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutionRegistry executionRegistry;
    private final DockerExpectation dockerExpectation;
    private final SystemIdentityService systemIdentity;
    private final TraySettingsService traySettings;
    private final TrayInstanceMonitor instanceMonitor;

    private TrayIcon trayIcon;
    private volatile TrayState previousState;
    private boolean initialized = false;
    private int targetIconSize;
    private final Map<List<InstanceLight>, Image> iconCache = new HashMap<>();

    public SystemTrayService(TrayConfig trayConfig,
                             WorkflowRunRepository runRepository,
                             WorkflowExecutionRegistry executionRegistry,
                             DockerExpectation dockerExpectation,
                             SystemIdentityService systemIdentity,
                             TraySettingsService traySettings,
                             TrayInstanceMonitor instanceMonitor) {
        this.trayConfig = trayConfig;
        this.runRepository = runRepository;
        this.executionRegistry = executionRegistry;
        this.dockerExpectation = dockerExpectation;
        this.systemIdentity = systemIdentity;
        this.traySettings = traySettings;
        this.instanceMonitor = instanceMonitor;
    }

    record TrayState(int activeRuns,
                     long runsToday,
                     long failedToday,
                     int pendingApprovals,
                     boolean dockerOk,
                     List<InstanceLight> instances) {
        int successPercent() {
            if (runsToday == 0) return 100;
            return (int) ((runsToday - failedToday) * 100 / runsToday);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!isSystemTraySupported()) {
            log.warn("SystemTray is not supported on this platform — tray icon disabled");
            return;
        }

        try {
            SystemTray systemTray = SystemTray.getSystemTray();
            Dimension traySize = systemTray.getTrayIconSize();
            int targetSize = Math.max(traySize.width, traySize.height);
            log.info("Tray icon target size: {}x{}", targetSize, targetSize);

            targetIconSize = targetSize;

            List<InstanceLight> starting = List.of(new InstanceLight(InstanceKind.DEV, LightColor.GRAY));
            Image icon = iconFor(starting);
            trayIcon = new TrayIcon(icon, "MySuricates | Starting...");
            trayIcon.setImageAutoSize(false);

            PopupMenu menu = buildMenu(new TrayState(0, 0, 0, 0, false, starting));
            trayIcon.setPopupMenu(menu);
            // Double-click on the icon or click on the notification (depending on the desktop): if an approval is waiting, go straight there
            trayIcon.addActionListener(e -> TrayLinks.open(TrayLinks.clickTarget(traySettings.browserBaseUrl(),
                    previousState != null ? previousState.pendingApprovals() : 0)));

            systemTray.add(trayIcon);
            initialized = true;
            log.info("System tray icon initialized");
        } catch (Exception e) {
            log.warn("Failed to initialize system tray icon: {}", e.getMessage());
        }
    }

    private boolean isSystemTraySupported() {
        try {
            return SystemTray.isSupported();
        } catch (AWTError | LinkageError e) { // LinkageError: toolkit init fails when DISPLAY has no X server
            log.warn("SystemTray support check failed: {}", e.getMessage());
            return false;
        }
    }

    private Image iconFor(List<InstanceLight> lights) {
        List<InstanceLight> key = List.copyOf(lights);
        return iconCache.computeIfAbsent(key, value -> TrayIconRenderer.render(value, targetIconSize));
    }

    @Scheduled(fixedRateString = "${automation.tray.poll-interval-ms:15000}")
    public void refreshStatus() {
        if (!initialized) return;

        try {
            TrayState state = collectStatus();
            String tooltip = buildTooltip(state);

            EventQueue.invokeLater(() -> {
                trayIcon.setImage(iconFor(state.instances()));
                trayIcon.setToolTip(tooltip);
                trayIcon.setPopupMenu(buildMenu(state));
            });

            emitNotifications(state);
            previousState = state;
        } catch (Exception e) {
            log.debug("Tray status refresh failed: {}", e.getMessage());
        }
    }

    TrayState collectStatus() {
        Instant todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();

        int activeRuns = executionRegistry.getActiveRunCount();
        long runsToday = runRepository.countByStartedAtAfter(todayStart);
        long failedToday = runRepository.countByStatusAndStartedAtAfter(RunStatus.FAILED, todayStart);
        int pendingApprovals = executionRegistry.getPendingApprovalCount();
        // Docker that is not set up is not a warning state, only a set-up one that does not respond (a0e85182)
        boolean dockerOk = dockerExpectation.probe() != DockerExpectation.State.DOWN;
        List<InstanceLight> instances = instanceMonitor.collect();

        return new TrayState(activeRuns, runsToday, failedToday, pendingApprovals, dockerOk, instances);
    }

    private String buildTooltip(TrayState state) {
        String instanceInfo = state.instances().stream()
                .map(light -> light.kind().label() + ": " + statusText(light.color()))
                .reduce((left, right) -> left + " | " + right)
                .orElse("Keine Instanz erkannt");
        return String.format("MySuricates v%s | %s | %d aktiv | %d%% Erfolg",
                systemIdentity.getVersion(), instanceInfo, state.activeRuns(), state.successPercent());
    }

    static String statusText(LightColor color) {
        return switch (color) {
            case GREEN -> "OK";
            case YELLOW -> "startet";
            case RED -> "offline";
            case GRAY -> "wird geprueft";
        };
    }

    private PopupMenu buildMenu(TrayState state) {
        return TrayMenuBuilder.build(state, systemIdentity.getVersion(), trayConfig,
                dockerExpectation, traySettings, this::exitFromTray);
    }

    private void exitFromTray() {
        log.info("Shutdown requested via system tray");
        cleanup();
        System.exit(0);
    }

    private void emitNotifications(TrayState state) {
        if (previousState == null) return;
        TraySettingsService.Preferences preferences = traySettings.current();
        if (!preferences.popupsEnabled()) return;

        if (preferences.notifySuccess() && previousState.activeRuns > 0 && state.activeRuns == 0) {
            trayIcon.displayMessage("MySuricates",
                    "Alle Workflows abgeschlossen",
                    TrayIcon.MessageType.INFO);
        }

        if (preferences.notifyFailures() && state.failedToday > previousState.failedToday) {
            long newFailures = state.failedToday - previousState.failedToday;
            trayIcon.displayMessage("MySuricates",
                    newFailures + " Workflow(s) fehlgeschlagen",
                    TrayIcon.MessageType.WARNING);
        }

        if (preferences.notifyApprovals() && state.pendingApprovals > previousState.pendingApprovals) {
            trayIcon.displayMessage("MySuricates",
                    state.pendingApprovals + " Freigabe(n) warten auf dich — Seitenleiste: Freigaben",
                    TrayIcon.MessageType.WARNING);
        }
    }
    /**
     * Shows a desktop notification via the system tray icon.
     * Silently does nothing if the tray is not available.
     */
    public void showNotification(String title, String message, boolean isError) {
        if (!initialized || trayIcon == null) return;
        TraySettingsService.Preferences preferences = traySettings.current();
        if (!preferences.popupsEnabled()
                || (isError && !preferences.notifyFailures())
                || (!isError && !preferences.notifySuccess())) return;
        java.awt.EventQueue.invokeLater(() -> {
            try {
                trayIcon.displayMessage(title, message,
                        isError ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO);
            } catch (Exception e) {
                log.debug("Failed to show desktop notification: {}", e.getMessage());
            }
        });
    }

    @PreDestroy
    public void cleanup() {
        if (initialized && trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
                log.info("System tray icon removed");
            } catch (Exception e) {
                log.debug("Failed to remove tray icon: {}", e.getMessage());
            }
        }
    }
}
