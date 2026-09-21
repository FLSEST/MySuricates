package com.automation.service.tray;

import com.automation.service.project.DockerExpectation;
import com.automation.config.TrayConfig;
import com.automation.entity.RunStatus;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
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

    private TrayIcon trayIcon;
    private volatile TrayState previousState;
    private boolean initialized = false;
    private volatile String stableVersion = null;
    private final Map<IconColor, Image> iconCache = new EnumMap<>(IconColor.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public SystemTrayService(TrayConfig trayConfig,
                             WorkflowRunRepository runRepository,
                             WorkflowExecutionRegistry executionRegistry,
                             DockerExpectation dockerExpectation,
                             SystemIdentityService systemIdentity,
                             TraySettingsService traySettings) {
        this.trayConfig = trayConfig;
        this.runRepository = runRepository;
        this.executionRegistry = executionRegistry;
        this.dockerExpectation = dockerExpectation;
        this.systemIdentity = systemIdentity;
        this.traySettings = traySettings;
    }

    record TrayState(int activeRuns, long runsToday, long failedToday, int pendingApprovals, boolean dockerOk, boolean frontendOk, boolean stableOk) {
        int successPercent() {
            if (runsToday == 0) return 100;
            return (int) ((runsToday - failedToday) * 100 / runsToday);
        }
    }

    enum IconColor { GREEN, YELLOW, RED, GRAY }

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

            loadIcons(targetSize);

            Image icon = iconCache.get(IconColor.GRAY);
            trayIcon = new TrayIcon(icon, "MySuricates | Starting...");
            trayIcon.setImageAutoSize(false);

            PopupMenu menu = buildMenu(new TrayState(0, 0, 0, 0, false, false, false));
            trayIcon.setPopupMenu(menu);
            // Doppelklick aufs Icon bzw. Klick auf die Meldung (je nach Desktop): wartet eine Freigabe, direkt dorthin
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

    private void loadIcons(int targetSize) {
        for (IconColor color : IconColor.values()) {
            iconCache.put(color, TrayIconRenderer.render(color, targetSize));
        }
        log.info("Rendered {} tray icons at {}x{}", iconCache.size(), targetSize, targetSize);
    }

    Image getIcon(IconColor color) {
        return iconCache.get(color);
    }

    @Scheduled(fixedRateString = "${automation.tray.poll-interval-ms:15000}")
    public void refreshStatus() {
        if (!initialized) return;

        try {
            TrayState state = collectStatus();
            IconColor color = determineIconColor(state);
            String tooltip = buildTooltip(state);

            EventQueue.invokeLater(() -> {
                trayIcon.setImage(iconCache.get(color));
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
        // Nicht eingerichtetes Docker ist kein Warnzustand, nur eingerichtetes, das nicht antwortet (a0e85182)
        boolean dockerOk = dockerExpectation.probe() != DockerExpectation.State.DOWN;
        boolean frontendOk = checkFrontend();
        boolean stableOk = checkStable();

        return new TrayState(activeRuns, runsToday, failedToday, pendingApprovals, dockerOk, frontendOk, stableOk);
    }

    IconColor determineIconColor(TrayState state) {
        if (state.failedToday > 0 && (previousState == null || state.failedToday > previousState.failedToday)) {
            return IconColor.RED;
        }
        if (!state.dockerOk || !state.frontendOk || !state.stableOk || state.pendingApprovals > 0) {
            return IconColor.YELLOW;
        }
        return IconColor.GREEN;
    }

    private String buildTooltip(TrayState state) {
        String devVersion = systemIdentity.getVersion();
        String stableInfo = state.stableOk
                ? "UP" + (stableVersion != null ? " v" + stableVersion : "")
                : "Offline";
        return String.format("MySuricates v%s | Dev: UP | Stable: %s | %d aktiv | %d%% Erfolg",
                devVersion, stableInfo, state.activeRuns, state.successPercent());
    }

    private PopupMenu buildMenu(TrayState state) {
        PopupMenu menu = new PopupMenu();

        MenuItem header = new MenuItem("MySuricates v" + systemIdentity.getVersion());
        header.setEnabled(false);
        menu.add(header);

        menu.addSeparator();

        String devStatus = String.format("Dev  [localhost:3000]  %s", "UP");
        MenuItem devItem = new MenuItem(devStatus);
        devItem.setEnabled(false);
        menu.add(devItem);

        String stableInfo = state.stableOk
                ? "UP" + (stableVersion != null ? " (v" + stableVersion + ")" : "")
                : "Offline";
        String stableStatus = String.format("Stable [localhost:9091]  %s", stableInfo);
        MenuItem stableItem = new MenuItem(stableStatus);
        stableItem.setEnabled(false);
        menu.add(stableItem);

        menu.addSeparator();

        MenuItem dockerItem = new MenuItem(String.format("Docker: %s | %d aktiv | %d%% Erfolg",
                !state.dockerOk ? "Offline" : dockerExpectation.isConfigured() ? "OK" : "nicht eingerichtet",
                state.activeRuns,
                state.successPercent()));
        dockerItem.setEnabled(false);
        menu.add(dockerItem);

        menu.addSeparator();

        String browserBaseUrl = traySettings.browserBaseUrl();

        if (state.pendingApprovals > 0) {
            MenuItem approvalItem = new MenuItem(state.pendingApprovals + " Freigabe(n) wartend");
            approvalItem.addActionListener(e -> TrayLinks.open(TrayLinks.approvals(browserBaseUrl)));
            menu.add(approvalItem);
            menu.addSeparator();
        }

        MenuItem openItem = new MenuItem("MySuricates oeffnen");
        openItem.addActionListener(e -> TrayLinks.open(browserBaseUrl));
        menu.add(openItem);

        MenuItem devUiItem = new MenuItem("Dev Web-UI oeffnen");
        devUiItem.addActionListener(e -> TrayLinks.open(trayConfig.getWebUiUrl()));
        menu.add(devUiItem);

        MenuItem stableUiItem = new MenuItem("Stable Web-UI oeffnen");
        stableUiItem.addActionListener(e -> TrayLinks.open(trayConfig.getStableUrl()));
        menu.add(stableUiItem);

        MenuItem activityItem = new MenuItem("Activity Log");
        activityItem.addActionListener(e -> TrayLinks.open(TrayLinks.route(browserBaseUrl, "/activity")));
        menu.add(activityItem);

        menu.addSeparator();

        MenuItem exitItem = new MenuItem("Beenden");
        exitItem.addActionListener(e -> {
            log.info("Shutdown requested via system tray");
            cleanup();
            System.exit(0);
        });
        menu.add(exitItem);

        return menu;
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


    boolean checkFrontend() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trayConfig.getWebUiUrl()))
                    .timeout(Duration.ofSeconds(3))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    boolean checkStable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trayConfig.getStableUrl() + "/api/v1/system/info"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 500) {
                extractStableVersion(response.body());
                return true;
            }
            return false;
        } catch (Exception e) {
            stableVersion = null;
            return false;
        }
    }

    private void extractStableVersion(String body) {
        try {
            // Simple JSON parsing for "version":"x.y.z" — avoids Jackson dependency
            int idx = body.indexOf("\"version\"");
            if (idx >= 0) {
                int colon = body.indexOf(':', idx);
                int firstQuote = body.indexOf('"', colon + 1);
                int lastQuote = body.indexOf('"', firstQuote + 1);
                if (firstQuote > 0 && lastQuote > firstQuote) {
                    stableVersion = body.substring(firstQuote + 1, lastQuote);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract stable version: {}", e.getMessage());
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
