package com.automation.service.system.settings;

import com.automation.config.DashboardLayoutConfig;
import com.automation.config.RunRetentionConfig;
import com.automation.config.TodoGhostWatchdogConfig;
import com.automation.config.TodoHygieneConfig;
import com.automation.config.TodoSimilarityConfig;
import com.automation.service.workflow.RunDeletionPolicy;
import org.junit.jupiter.api.Test;

import static com.automation.service.system.settings.SettingsGroupsTestSupport.apply;
import static com.automation.service.system.settings.SettingsGroupsTestSupport.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class HousekeepingSettingsTest {

    private final TodoHygieneConfig hygiene = new TodoHygieneConfig();
    private final RunRetentionConfig retention = new RunRetentionConfig();
    private final DashboardLayoutConfig layout = new DashboardLayoutConfig();
    private final TodoGhostWatchdogConfig watchdog = new TodoGhostWatchdogConfig();
    private final TodoSimilarityConfig similarity = new TodoSimilarityConfig();
    private final RunDeletionPolicy policy = mock(RunDeletionPolicy.class);
    private final HousekeepingSettings settings =
            new HousekeepingSettings(hygiene, retention, layout, watchdog, similarity, policy);

    @Test
    void appliersSchreibenJedenWertInSeineConfig() {
        apply(settings, "todo-hygiene-enabled", "1");
        apply(settings, "todo-stale-days", "12");
        apply(settings, "todo-hygiene-max-per-run", "7");
        apply(settings, "run-retention-days", "45");
        apply(settings, "run-retention-max-per-night", "9");
        apply(settings, "dashboard-layout-retention-count", "3");
        apply(settings, "todo-watchdog-enabled", "0");
        apply(settings, "todo-watchdog-stale-hours", "5");
        apply(settings, "todo-similarity-keyword-threshold", "0.35");

        assertThat(hygiene.isEnabled()).isTrue();
        assertThat(hygiene.getStaleDays()).isEqualTo(12);
        assertThat(hygiene.getMaxPerRun()).isEqualTo(7);
        assertThat(retention.getRetentionDays()).isEqualTo(45);
        assertThat(retention.getMaxPerNight()).isEqualTo(9);
        assertThat(layout.getRetentionCount()).isEqualTo(3);
        assertThat(watchdog.isEnabled()).isFalse();
        assertThat(watchdog.getStaleHours()).isEqualTo(5);
        assertThat(similarity.getKeywordThreshold()).isEqualTo(0.35);
    }

    @Test
    void schalterSindNurBeiEinsAn() {
        apply(settings, "todo-hygiene-enabled", "1");
        apply(settings, "todo-hygiene-enabled", "2");

        assertThat(hygiene.isEnabled()).isFalse();
    }

    @Test
    void nichtNumerischerWertWirftNumberFormatException() {
        assertThatThrownBy(() -> apply(settings, "todo-stale-days", "abc"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void currentValuesSpiegelnDieConfigsMitSchalternAlsZahl() {
        hygiene.setEnabled(true);
        watchdog.setEnabled(false);
        retention.setRetentionDays(60);
        similarity.setKeywordThreshold(0.5);

        var v = values(settings);

        assertThat(v).containsEntry("todoHygieneEnabled", 1)
                .containsEntry("todoWatchdogEnabled", 0)
                .containsEntry("runRetentionDays", 60)
                .containsEntry("todoSimilarityKeywordThreshold", 0.5)
                .containsEntry("todoStaleDays", hygiene.getStaleDays())
                .containsEntry("dashboardLayoutRetentionCount", layout.getRetentionCount())
                .containsKeys("todoHygieneMaxPerRun", "runRetentionMaxPerNight", "todoWatchdogStaleHours");
    }

    @Test
    void jederApplierHatEinenWertImAnzeigeSchluesselraum() {
        assertThat(settings.appliers()).hasSize(9);
        assertThat(values(settings)).hasSize(9);
    }

    @Test
    void guardPruefungDerRetentionLaeuftGegenAktuellenUndNeuenWert() {
        retention.setRetentionDays(90);

        settings.guard("run-retention-days", "30");

        verify(policy).requireRetentionChangeAllowed(90, 30);
    }

    @Test
    void guardReichtDieAblehnungDerPolicyDurch() {
        var boom = new IllegalStateException("verboten");
        org.mockito.Mockito.doThrow(boom).when(policy).requireRetentionChangeAllowed(90, 1);

        assertThatThrownBy(() -> settings.guard("run-retention-days", "1")).isSameAs(boom);
    }

    @Test
    void guardIgnoriertAndereSchluessel() {
        settings.guard("todo-stale-days", "abc");

        verifyNoInteractions(policy);
        verify(policy, never()).requireRetentionChangeAllowed(0, 0);
    }
}
