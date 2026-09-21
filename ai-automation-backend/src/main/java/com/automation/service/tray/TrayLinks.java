package com.automation.service.tray;

import com.automation.config.TrayConfig;
import lombok.extern.slf4j.Slf4j;

import java.awt.Desktop;
import java.net.URI;

/** Wohin Tray-Menü und Tray-Klick führen, und das Öffnen im Browser. Aus {@link SystemTrayService} gezogen (Größen-Ratchet). */
@Slf4j
final class TrayLinks {

    private TrayLinks() {
    }

    static String approvals(TrayConfig config) {
        return config.getWebUiUrl() + "/approvals";
    }

    /** Klick aufs Icon bzw. auf die Meldung: Wartet eine Freigabe, direkt auf die Freigaben-Seite, sonst die Web-UI. */
    static String clickTarget(TrayConfig config, int pendingApprovals) {
        return pendingApprovals > 0 ? approvals(config) : config.getWebUiUrl();
    }

    static void open(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            log.warn("Failed to open URL {}: {}", url, e.getMessage());
        }
    }
}
