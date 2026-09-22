package com.automation.service.tray;

import com.automation.config.TrayConfig;
import com.automation.service.project.DockerExpectation;

import java.awt.CheckboxMenuItem;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;

/** Builds the native tray menu with status details and a quick popup switch. */
final class TrayMenuBuilder {

    private TrayMenuBuilder() { }

    static PopupMenu build(SystemTrayService.TrayState state,
                           String version,
                           TrayConfig trayConfig,
                           DockerExpectation dockerExpectation,
                           TraySettingsService traySettings,
                           Runnable exitApplication) {
        PopupMenu menu = new PopupMenu();
        addStatus(menu, state, version, dockerExpectation);

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

        TraySettingsService.Preferences preferences = traySettings.current();
        CheckboxMenuItem popups = new CheckboxMenuItem("Desktop-Pop-ups", preferences.popupsEnabled());
        popups.addItemListener(e -> traySettings.setPopupsEnabled(popups.getState()));
        menu.add(popups);

        MenuItem traySettingsItem = new MenuItem("Tray-Einstellungen ...");
        traySettingsItem.addActionListener(e ->
                TrayLinks.open(TrayLinks.route(browserBaseUrl, "/settings#tray")));
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

    private static void addStatus(PopupMenu menu,
                                  SystemTrayService.TrayState state,
                                  String version,
                                  DockerExpectation dockerExpectation) {
        MenuItem header = new MenuItem("MySuricates v" + version);
        header.setEnabled(false);
        menu.add(header);
        menu.addSeparator();

        state.instances().forEach(light -> {
            MenuItem status = new MenuItem(light.kind().label() + ": "
                    + SystemTrayService.statusText(light.color()));
            status.setEnabled(false);
            menu.add(status);
        });

        MenuItem runs = new MenuItem(String.format("%d aktiv | %d%% Erfolg",
                state.activeRuns(), state.successPercent()));
        runs.setEnabled(false);
        menu.add(runs);

        MenuItem docker = new MenuItem("Docker: " + (!state.dockerOk()
                ? "Offline" : dockerExpectation.isConfigured() ? "OK" : "nicht eingerichtet"));
        docker.setEnabled(false);
        menu.add(docker);
        menu.addSeparator();
    }
}
