package com.automation.service.tray;

import com.automation.config.TrayConfig;
import com.automation.service.project.DockerExpectation;

import java.awt.CheckboxMenuItem;
import java.awt.EventQueue;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.util.function.Consumer;

/** Builds the native tray menu, including directly editable notification preferences. */
final class TrayMenuBuilder {

    private TrayMenuBuilder() { }

    static PopupMenu build(SystemTrayService.TrayState state,
                           String version,
                           String stableVersion,
                           TrayConfig trayConfig,
                           DockerExpectation dockerExpectation,
                           TraySettingsService traySettings,
                           Runnable rebuildMenu,
                           Runnable exitApplication) {
        PopupMenu menu = new PopupMenu();
        addStatus(menu, state, version, stableVersion, dockerExpectation);

        String browserBaseUrl = traySettings.browserBaseUrl();
        if (state.pendingApprovals() > 0) {
            MenuItem approvals = new MenuItem(state.pendingApprovals() + " Freigabe(n) wartend");
            approvals.addActionListener(e -> TrayLinks.open(TrayLinks.approvals(browserBaseUrl)));
            menu.add(approvals);
            menu.addSeparator();
        }

        MenuItem open = new MenuItem("MySuricates oeffnen");
        open.addActionListener(e -> TrayLinks.open(browserBaseUrl));
        menu.add(open);

        Menu notifications = notificationMenu(traySettings, rebuildMenu);
        menu.add(notifications);

        MenuItem traySettingsItem = new MenuItem("Tray-Einstellungen ...");
        traySettingsItem.addActionListener(e ->
                TrayLinks.open(TrayLinks.route(browserBaseUrl, "/settings#tray-settings")));
        menu.add(traySettingsItem);

        MenuItem activity = new MenuItem("Activity Log");
        activity.addActionListener(e -> TrayLinks.open(TrayLinks.route(browserBaseUrl, "/activity")));
        menu.add(activity);

        Menu environments = new Menu("Instanzen");
        MenuItem dev = new MenuItem("Dev Web-UI oeffnen");
        dev.addActionListener(e -> TrayLinks.open(trayConfig.getWebUiUrl()));
        environments.add(dev);
        MenuItem stable = new MenuItem("Stable Web-UI oeffnen");
        stable.addActionListener(e -> TrayLinks.open(trayConfig.getStableUrl()));
        environments.add(stable);
        menu.add(environments);

        menu.addSeparator();
        MenuItem exit = new MenuItem("Beenden");
        exit.addActionListener(e -> exitApplication.run());
        menu.add(exit);
        return menu;
    }

    private static Menu notificationMenu(TraySettingsService traySettings, Runnable rebuildMenu) {
        TraySettingsService.Preferences preferences = traySettings.current();
        Menu notifications = new Menu("Benachrichtigungen");
        notifications.add(toggle("Alle Desktop-Pop-ups", preferences.popupsEnabled(), true,
                traySettings::setPopupsEnabled, rebuildMenu));
        notifications.addSeparator();
        notifications.add(toggle("Wartende Freigaben", preferences.notifyApprovals(),
                preferences.popupsEnabled(), traySettings::setNotifyApprovals, rebuildMenu));
        notifications.add(toggle("Fehlgeschlagene Workflows", preferences.notifyFailures(),
                preferences.popupsEnabled(), traySettings::setNotifyFailures, rebuildMenu));
        notifications.add(toggle("Erfolgreiche Workflows", preferences.notifySuccess(),
                preferences.popupsEnabled(), traySettings::setNotifySuccess, rebuildMenu));
        return notifications;
    }

    private static CheckboxMenuItem toggle(String label,
                                           boolean selected,
                                           boolean enabled,
                                           Consumer<Boolean> save,
                                           Runnable rebuildMenu) {
        CheckboxMenuItem item = new CheckboxMenuItem(label, selected);
        item.setEnabled(enabled);
        item.addItemListener(e -> {
            save.accept(item.getState());
            EventQueue.invokeLater(rebuildMenu);
        });
        return item;
    }

    private static void addStatus(PopupMenu menu,
                                  SystemTrayService.TrayState state,
                                  String version,
                                  String stableVersion,
                                  DockerExpectation dockerExpectation) {
        MenuItem header = new MenuItem("MySuricates v" + version);
        header.setEnabled(false);
        menu.add(header);
        menu.addSeparator();

        String stable = state.stableOk()
                ? "UP" + (stableVersion != null ? " (v" + stableVersion + ")" : "")
                : "Offline";
        MenuItem status = new MenuItem(String.format("Dev: UP | Stable: %s | %d aktiv | %d%% Erfolg",
                stable, state.activeRuns(), state.successPercent()));
        status.setEnabled(false);
        menu.add(status);

        MenuItem docker = new MenuItem("Docker: " + (!state.dockerOk()
                ? "Offline" : dockerExpectation.isConfigured() ? "OK" : "nicht eingerichtet"));
        docker.setEnabled(false);
        menu.add(docker);
        menu.addSeparator();
    }
}
