package com.automation.service.system.settings;

import com.automation.config.RequestMetricsConfig;
import org.junit.jupiter.api.Test;

import static com.automation.service.system.settings.SettingsGroupsTestSupport.apply;
import static com.automation.service.system.settings.SettingsGroupsTestSupport.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestMetricsSettingsTest {

    private final RequestMetricsConfig config = new RequestMetricsConfig();
    private final RequestMetricsSettings settings = new RequestMetricsSettings(config);

    @Test
    void appliersSchreibenAlleWerteInDieConfig() {
        apply(settings, "request-metrics-enabled", "0");
        apply(settings, "request-metrics-retention-days", "14");
        apply(settings, "request-metrics-slow-request-ms", "3000000000");
        apply(settings, "request-metrics-drift-detection-enabled", "0");
        apply(settings, "request-metrics-drift-factor", "3.5");
        apply(settings, "request-metrics-drift-min-calls", "40");
        apply(settings, "request-metrics-baseline-days", "10");
        apply(settings, "request-metrics-trend-min-daily-calls", "8");

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getRetentionDays()).isEqualTo(14);
        assertThat(config.getSlowRequestMs()).isEqualTo(3_000_000_000L);
        assertThat(config.isDriftDetectionEnabled()).isFalse();
        assertThat(config.getDriftFactor()).isEqualTo(3.5);
        assertThat(config.getDriftMinCalls()).isEqualTo(40);
        assertThat(config.getBaselineDays()).isEqualTo(10);
        assertThat(config.getTrendMinDailyCalls()).isEqualTo(8);
    }

    @Test
    void schalterWerdenMitEinsWiederAngeschaltet() {
        config.setEnabled(false);

        apply(settings, "request-metrics-enabled", "1");

        assertThat(config.isEnabled()).isTrue();
    }

    @Test
    void ungueltigeZahlWirft() {
        assertThatThrownBy(() -> apply(settings, "request-metrics-drift-factor", "x"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void zeigtBewusstKeineWerteAn() {
        assertThat(values(settings)).isEmpty();
        assertThat(settings.appliers()).hasSize(8);
    }
}
