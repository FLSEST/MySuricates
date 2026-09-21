package com.automation.service.tray;

import lombok.extern.slf4j.Slf4j;

import java.awt.Desktop;
import java.net.URI;

/** Wohin Tray-Menü und Tray-Klick führen, und das Öffnen im Browser. Aus {@link SystemTrayService} gezogen (Größen-Ratchet). */
@Slf4j
final class TrayLinks {

    private TrayLinks() {
    }

    static String approvals(String baseUrl) {
        return route(baseUrl, "/approvals");
    }

    /** Klick aufs Icon bzw. auf die Meldung: Wartet eine Freigabe, direkt auf die Freigaben-Seite, sonst die Web-UI. */
    static String clickTarget(String baseUrl, int pendingApprovals) {
        return pendingApprovals > 0 ? approvals(baseUrl) : stripTrailingSlash(baseUrl);
    }

    static String route(String baseUrl, String path) {
        return stripTrailingSlash(baseUrl) + (path.startsWith("/") ? path : "/" + path);
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
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
