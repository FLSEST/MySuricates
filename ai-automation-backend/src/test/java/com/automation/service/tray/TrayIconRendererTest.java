package com.automation.service.tray;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class TrayIconRendererTest {

    @Test
    void render_createsTransparentSquareIcon() {
        BufferedImage icon = TrayIconRenderer.render(SystemTrayService.IconColor.GREEN, 32);

        assertThat(icon.getWidth()).isEqualTo(32);
        assertThat(icon.getHeight()).isEqualTo(32);
        assertThat((icon.getRGB(0, 0) >>> 24) & 0xff).isZero();
        assertThat((icon.getRGB(16, 12) >>> 24) & 0xff).isGreaterThan(0);
    }

    @Test
    void render_changesOnlyStatusMeaningBetweenHealthyAndFailedIcons() {
        BufferedImage green = TrayIconRenderer.render(SystemTrayService.IconColor.GREEN, 64);
        BufferedImage red = TrayIconRenderer.render(SystemTrayService.IconColor.RED, 64);

        assertThat(green.getRGB(52, 52)).isNotEqualTo(red.getRGB(52, 52));
        assertThat(green.getRGB(32, 26)).isEqualTo(red.getRGB(32, 26));
    }
}
