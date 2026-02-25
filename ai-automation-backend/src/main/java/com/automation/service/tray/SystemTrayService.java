package com.automation.service.tray;

import com.automation.config.TrayConfig;
import com.automation.entity.RunStatus;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.github.dockerjava.api.DockerClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
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
    private final WorkflowExecutor workflowExecutor;
    private final DockerClient dockerClient;

    private TrayIcon trayIcon;
    private TrayState previousState;
    private boolean initialized = false;
    private final Map<IconColor, Image> iconCache = new EnumMap<>(IconColor.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public SystemTrayService(TrayConfig trayConfig,
                             WorkflowRunRepository runRepository,
                             @Lazy WorkflowExecutor workflowExecutor,
                             DockerClient dockerClient) {
        this.trayConfig = trayConfig;
        this.runRepository = runRepository;
        this.workflowExecutor = workflowExecutor;
        this.dockerClient = dockerClient;
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
        if (!SystemTray.isSupported()) {
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

            systemTray.add(trayIcon);
            initialized = true;
            log.info("System tray icon initialized");
        } catch (Exception e) {
            log.warn("Failed to initialize system tray icon: {}", e.getMessage());
        }
    }

    private void loadIcons(int targetSize) throws IOException {
        for (IconColor color : IconColor.values()) {
            String path = "tray/tray-" + color.name().toLowerCase() + ".png";
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
                if (is == null) {
                    throw new IOException("Tray icon not found: " + path);
                }
                BufferedImage source = ImageIO.read(is);
                iconCache.put(color, progressiveScale(source, targetSize));
            }
        }
        log.info("Loaded and scaled {} tray icons to {}x{}", iconCache.size(), targetSize, targetSize);
    }

    /**
     * Progressive bilinear downscale — halves the image repeatedly until close
     * to the target size, then does one final scale. This produces much sharper
     * results than a single large downscale.
     */
    private static BufferedImage progressiveScale(BufferedImage source, int targetSize) {
        BufferedImage current = source;
        int w = current.getWidth();
        int h = current.getHeight();

        while (w / 2 >= targetSize && h / 2 >= targetSize) {
            w /= 2;
            h /= 2;
            BufferedImage half = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = half.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = half;
        }

        if (w != targetSize || h != targetSize) {
            BufferedImage result = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = result.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(current, 0, 0, targetSize, targetSize, null);
            g.dispose();
            return result;
        }

        return current;
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

        int activeRuns = workflowExecutor.getActiveRunCount();
        long runsToday = runRepository.countByStartedAtAfter(todayStart);
        long failedToday = runRepository.countByStatusAndStartedAtAfter(RunStatus.FAILED, todayStart);
        int pendingApprovals = workflowExecutor.getPendingApprovalCount();
        boolean dockerOk = checkDocker();
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
        return String.format("MySuricates | Dev: UP | Stable: %s | %d aktiv | %d%% Erfolg",
                state.stableOk ? "UP" : "Offline",
                state.activeRuns,
                state.successPercent());
    }

    private PopupMenu buildMenu(TrayState state) {
        PopupMenu menu = new PopupMenu();

        MenuItem header = new MenuItem("MySuricates");
        header.setEnabled(false);
        menu.add(header);

        menu.addSeparator();

        String devStatus = String.format("Dev  [localhost:3000]  %s", "UP");
        MenuItem devItem = new MenuItem(devStatus);
        devItem.setEnabled(false);
        menu.add(devItem);

        String stableStatus = String.format("Stable [localhost:9091]  %s", state.stableOk ? "UP" : "Offline");
        MenuItem stableItem = new MenuItem(stableStatus);
        stableItem.setEnabled(false);
        menu.add(stableItem);

        menu.addSeparator();

        MenuItem dockerItem = new MenuItem(String.format("Docker: %s | %d aktiv | %d%% Erfolg",
                state.dockerOk ? "OK" : "Offline",
                state.activeRuns,
                state.successPercent()));
        dockerItem.setEnabled(false);
        menu.add(dockerItem);

        menu.addSeparator();

        if (state.pendingApprovals > 0) {
            MenuItem approvalItem = new MenuItem(state.pendingApprovals + " Approval(s) wartend");
            approvalItem.addActionListener(e -> openUrl(trayConfig.getWebUiUrl()));
            menu.add(approvalItem);
            menu.addSeparator();
        }

        MenuItem devUiItem = new MenuItem("Dev Web-UI oeffnen");
        devUiItem.addActionListener(e -> openUrl(trayConfig.getWebUiUrl()));
        menu.add(devUiItem);

        MenuItem stableUiItem = new MenuItem("Stable Web-UI oeffnen");
        stableUiItem.addActionListener(e -> openUrl(trayConfig.getStableUrl()));
        menu.add(stableUiItem);

        MenuItem dashboardDevItem = new MenuItem("Dashboard (Dev)");
        dashboardDevItem.addActionListener(e -> openUrl(trayConfig.getWebUiUrl() + "/"));
        menu.add(dashboardDevItem);

        MenuItem dashboardStableItem = new MenuItem("Dashboard (Stable)");
        dashboardStableItem.addActionListener(e -> openUrl(trayConfig.getStableUrl() + "/"));
        menu.add(dashboardStableItem);

        MenuItem activityItem = new MenuItem("Activity Log");
        activityItem.addActionListener(e -> openUrl(trayConfig.getWebUiUrl() + "/activity"));
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

        if (previousState.activeRuns > 0 && state.activeRuns == 0) {
            trayIcon.displayMessage("MySuricates",
                    "Alle Workflows abgeschlossen",
                    TrayIcon.MessageType.INFO);
        }

        if (state.failedToday > previousState.failedToday) {
            long newFailures = state.failedToday - previousState.failedToday;
            trayIcon.displayMessage("MySuricates",
                    newFailures + " Workflow(s) fehlgeschlagen",
                    TrayIcon.MessageType.WARNING);
        }

        if (state.pendingApprovals > previousState.pendingApprovals) {
            trayIcon.displayMessage("MySuricates",
                    state.pendingApprovals + " Approval(s) warten auf Eingabe",
                    TrayIcon.MessageType.WARNING);
        }
    }

    private boolean checkDocker() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
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
                    .uri(URI.create(trayConfig.getStableUrl() + "/api/v1/system/health"))
                    .timeout(Duration.ofSeconds(3))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            log.warn("Failed to open URL {}: {}", url, e.getMessage());
        }
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
