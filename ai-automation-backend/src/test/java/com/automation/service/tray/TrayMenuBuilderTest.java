package com.automation.service.tray;

import com.automation.service.project.DockerExpectation;
import com.automation.service.tray.SystemTrayService.TrayState;
import com.automation.service.tray.TrayInstanceMonitor.InstanceKind;
import com.automation.service.tray.TrayInstanceMonitor.InstanceLight;
import com.automation.service.tray.TrayInstanceMonitor.LightColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Beschriftungen des Tray-Menüs. Das AWT-Menü selbst ({@code PopupMenu}, {@code MenuItem}) lässt
 * sich headless nicht anlegen (HeadlessException); getestet wird daher die Zustandslogik, die der
 * Builder für die Zeilen benutzt.
 */
class TrayMenuBuilderTest {

    private static TrayState state(int active, long today, long failed, int pending, boolean dockerOk,
                                   InstanceLight... lights) {
        return new TrayState(active, today, failed, pending, dockerOk, List.of(lights));
    }

    @Test
    void headerText_carriesTheVersion() {
        assertThat(TrayMenuBuilder.headerText("0.7.7")).isEqualTo("MySuricates v0.7.7");
    }

    @Test
    void approvalsText_showsTheCount() {
        assertThat(TrayMenuBuilder.approvalsText(state(0, 0, 0, 3, true))).isEqualTo("3 Freigabe(n) wartend");
    }

    @Test
    void instanceText_combinesKindLabelAndColorText() {
        assertThat(TrayMenuBuilder.instanceText(new InstanceLight(InstanceKind.STABLE, LightColor.GREEN)))
                .isEqualTo("Stable: OK");
        assertThat(TrayMenuBuilder.instanceText(new InstanceLight(InstanceKind.DEV, LightColor.RED)))
                .isEqualTo("Dev: offline");
        assertThat(TrayMenuBuilder.instanceText(new InstanceLight(InstanceKind.TEST, LightColor.GRAY)))
                .isEqualTo("Test: wird geprueft");
    }

    @Test
    void runsText_showsActiveRunsAndSuccessRate() {
        assertThat(TrayMenuBuilder.runsText(state(2, 20, 5, 0, true))).isEqualTo("2 aktiv | 75% Erfolg");
        assertThat(TrayMenuBuilder.runsText(state(0, 0, 0, 0, true))).isEqualTo("0 aktiv | 100% Erfolg");
    }

    @Test
    void dockerText_distinguishesOfflineConfiguredAndNotSetUp() {
        DockerExpectation configured = new DockerExpectation(null, "tcp://localhost:2375");
        DockerExpectation notSetUp = new DockerExpectation(null, "");

        assertThat(TrayMenuBuilder.dockerText(state(0, 0, 0, 0, false), configured)).isEqualTo("Docker: Offline");
        assertThat(TrayMenuBuilder.dockerText(state(0, 0, 0, 0, true), configured)).isEqualTo("Docker: OK");
        assertThat(TrayMenuBuilder.dockerText(state(0, 0, 0, 0, true), notSetUp))
                .isEqualTo("Docker: nicht eingerichtet");
    }
}
