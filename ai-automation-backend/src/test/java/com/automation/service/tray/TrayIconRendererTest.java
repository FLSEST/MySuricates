package com.automation.service.tray;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static com.automation.service.tray.TrayInstanceMonitor.InstanceKind.DEV;
import static com.automation.service.tray.TrayInstanceMonitor.InstanceKind.STABLE;
import static com.automation.service.tray.TrayInstanceMonitor.InstanceKind.TEST;
import static com.automation.service.tray.TrayInstanceMonitor.LightColor.GREEN;
import static com.automation.service.tray.TrayInstanceMonitor.LightColor.RED;
import static com.automation.service.tray.TrayInstanceMonitor.LightColor.YELLOW;

import static org.assertj.core.api.Assertions.assertThat;

class TrayIconRendererTest {

    @Test
    void render_createsOpaqueDarkSquareIconForTrayImplementationsWithoutAlpha() {
        BufferedImage icon = TrayIconRenderer.render(List.of(new TrayInstanceMonitor.InstanceLight(DEV, GREEN)), 32);

        assertThat(icon.getWidth()).isEqualTo(32);
        assertThat(icon.getHeight()).isEqualTo(32);
        assertThat((icon.getRGB(0, 0) >>> 24) & 0xff).isEqualTo(255);
        assertThat(icon.getRGB(0, 0) & 0xffffff).isEqualTo(0x1A1410);
        assertThat((icon.getRGB(16, 12) >>> 24) & 0xff).isGreaterThan(0);
    }

    @Test
    void render_placesDevTestAndStableLightsInFixedOrder() {
        BufferedImage icon = TrayIconRenderer.render(List.of(
                new TrayInstanceMonitor.InstanceLight(DEV, GREEN),
                new TrayInstanceMonitor.InstanceLight(TEST, YELLOW),
                new TrayInstanceMonitor.InstanceLight(STABLE, RED)
        ), 256);

        assertThat(icon.getRGB(76, 220) & 0xffffff).isEqualTo(0x36C98F);
        assertThat(icon.getRGB(128, 220) & 0xffffff).isEqualTo(0xF3B63F);
        assertThat(icon.getRGB(180, 220) & 0xffffff).isEqualTo(0xEF5A5A);
    }

    @Test
    void render_centersSingleDevLight() {
        BufferedImage icon = TrayIconRenderer.render(
                List.of(new TrayInstanceMonitor.InstanceLight(DEV, GREEN)), 256);

        assertThat(TrayIconRenderer.indicatorCenterX(0, 1)).isEqualTo(128);
        assertThat(icon.getRGB(128, 220) & 0xffffff).isEqualTo(0x36C98F);
    }
}
