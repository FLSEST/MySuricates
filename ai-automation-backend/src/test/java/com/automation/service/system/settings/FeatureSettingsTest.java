package com.automation.service.system.settings;

import com.automation.config.AutoResumeConfig;
import com.automation.config.CompressionConfig;
import com.automation.config.DashboardReportAssistConfig;
import com.automation.config.FactoryConfig;
import com.automation.config.SchedulerConfig;
import com.automation.config.SummarizationConfig;
import com.automation.config.WishConfig;
import com.automation.service.ai.catalog.ModelCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.automation.service.system.settings.SettingsGroupsTestSupport.apply;
import static com.automation.service.system.settings.SettingsGroupsTestSupport.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureSettingsTest {

    private final SchedulerConfig scheduler = new SchedulerConfig();
    private final SummarizationConfig summarization = new SummarizationConfig();
    private final FactoryConfig factory = new FactoryConfig();
    private final WishConfig wish = new WishConfig();
    private final ModelCatalogService modelCatalog = mock(ModelCatalogService.class);
    private final CompressionConfig compression = new CompressionConfig();
    private final DashboardReportAssistConfig reportAssist = new DashboardReportAssistConfig();
    private final AutoResumeConfig autoResume = new AutoResumeConfig();
    private final FeatureSettings settings = new FeatureSettings(scheduler, summarization, factory, wish,
            modelCatalog, compression, reportAssist, autoResume);

    @Test
    void appliersSchreibenJedenWertInSeineConfig() {
        apply(settings, "scheduler-catch-up-on-resume", "0");
        apply(settings, "scheduler-resume-grace-minutes", "15");
        apply(settings, "run-auto-resume-enabled", "0");
        apply(settings, "run-auto-resume-delay-seconds", "120");
        apply(settings, "summarization-timeout-seconds", "90");
        apply(settings, "factory-timeout-seconds", "1200");
        apply(settings, "wish-total-timeout-minutes", "60");
        apply(settings, "wish-workflow-wait-minutes", "10");
        apply(settings, "wish-default-shell-timeout-seconds", "300");
        apply(settings, "wish-default-agentic-total-timeout-seconds", "1800");
        apply(settings, "model-catalog-ttl-seconds", "3600");
        apply(settings, "compress-ai-max-input-chars", "20000");
        apply(settings, "dashboard-report-assist-enabled", "0");

        assertThat(scheduler.isCatchUpOnResume()).isFalse();
        assertThat(scheduler.getResumeGraceMinutes()).isEqualTo(15);
        assertThat(autoResume.isEnabled()).isFalse();
        assertThat(autoResume.getDelaySeconds()).isEqualTo(120);
        assertThat(summarization.getTimeoutSeconds()).isEqualTo(90);
        assertThat(factory.getTimeoutSeconds()).isEqualTo(1200);
        assertThat(wish.getTotalTimeoutMinutes()).isEqualTo(60);
        assertThat(wish.getWorkflowWaitMinutes()).isEqualTo(10);
        assertThat(wish.getDefaultShellTimeoutSeconds()).isEqualTo(300);
        assertThat(wish.getDefaultAgenticTotalTimeoutSeconds()).isEqualTo(1800);
        verify(modelCatalog).setTtlSeconds(3600);
        assertThat(compression.getMaxAiInputChars()).isEqualTo(20_000);
        assertThat(reportAssist.isEnabled()).isFalse();
    }

    @Test
    void schalterSindNurBeiEinsAn() {
        for (String key : List.of("scheduler-catch-up-on-resume", "run-auto-resume-enabled",
                "dashboard-report-assist-enabled")) {
            apply(settings, key, "0");
            apply(settings, key, "1");
        }
        assertThat(scheduler.isCatchUpOnResume()).isTrue();
        assertThat(autoResume.isEnabled()).isTrue();
        assertThat(reportAssist.isEnabled()).isTrue();

        apply(settings, "run-auto-resume-enabled", "2");

        assertThat(autoResume.isEnabled()).isFalse();
    }

    @Test
    void nichtNumerischerWertWirftUndLaesstDieConfigStehen() {
        assertThatThrownBy(() -> apply(settings, "run-auto-resume-delay-seconds", "bald"))
                .isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> apply(settings, "run-auto-resume-enabled", "true"))
                .isInstanceOf(NumberFormatException.class);

        assertThat(autoResume.getDelaySeconds()).isEqualTo(30);
        assertThat(autoResume.isEnabled()).isTrue();
    }

    @Test
    void currentValuesSpiegelnDieConfigsMitSchalternAlsZahl() {
        scheduler.setCatchUpOnResume(false);
        autoResume.setEnabled(true);
        autoResume.setDelaySeconds(45);
        reportAssist.setEnabled(false);
        when(modelCatalog.getTtlSeconds()).thenReturn(600);

        var v = values(settings);

        assertThat(v).containsEntry("schedulerCatchUpOnResume", 0)
                .containsEntry("runAutoResumeEnabled", 1)
                .containsEntry("runAutoResumeDelaySeconds", 45L)
                .containsEntry("dashboardReportAssistEnabled", 0)
                .containsEntry("modelCatalogTtlSeconds", 600)
                .containsEntry("schedulerResumeGraceMinutes", scheduler.getResumeGraceMinutes())
                .containsEntry("summarizationTimeoutSeconds", summarization.getTimeoutSeconds())
                .containsEntry("factoryTimeoutSeconds", factory.getTimeoutSeconds())
                .containsEntry("wishTotalTimeoutMinutes", wish.getTotalTimeoutMinutes())
                .containsEntry("wishWorkflowWaitMinutes", wish.getWorkflowWaitMinutes())
                .containsEntry("wishDefaultShellTimeoutSeconds", wish.getDefaultShellTimeoutSeconds())
                .containsEntry("wishDefaultAgenticTotalTimeoutSeconds", wish.getDefaultAgenticTotalTimeoutSeconds())
                .containsEntry("compressAiMaxInputChars", compression.getMaxAiInputChars());
    }

    @Test
    void jederApplierHatEinenWertImAnzeigeSchluesselraum() {
        assertThat(settings.appliers()).hasSize(13);
        assertThat(values(settings)).hasSize(13);
    }

    @Test
    void anzeigeSchluesselSindDieCamelCaseFormDerApplierSchluessel() {
        List<String> camel = settings.appliers().keySet().stream().map(FeatureSettingsTest::camelCase).toList();

        assertThat(values(settings).keySet()).containsExactlyInAnyOrderElementsOf(camel);
    }

    @Test
    void schreibenUndLesenErgibtDenselbenWert() {
        apply(settings, "run-auto-resume-delay-seconds", "0");
        apply(settings, "scheduler-resume-grace-minutes", "0");

        assertThat(values(settings)).containsEntry("runAutoResumeDelaySeconds", 0L)
                .containsEntry("schedulerResumeGraceMinutes", 0L);
    }

    @Test
    void guardLaesstAllesDurch() {
        assertThatCode(() -> settings.guard("run-auto-resume-delay-seconds", "-1")).doesNotThrowAnyException();
    }

    private static String camelCase(String kebab) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : kebab.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
