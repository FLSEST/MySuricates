package com.automation.service.tray;

import lombok.extern.slf4j.Slf4j;

import java.awt.Desktop;
import java.net.URI;

/** Where the tray menu and tray click lead, and opening in the browser. Extracted from {@link SystemTrayService} (size ratchet). */
@Slf4j
final class TrayLinks {

    private TrayLinks() {
    }

    static String approvals(String baseUrl) {
        return route(baseUrl, "/approvals");
    }

    /** Click on the icon or the notification: if an approval is waiting, straight to the approvals page, otherwise the web UI. */
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
