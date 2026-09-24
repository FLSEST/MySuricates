package com.automation.service.system.settings;

import com.automation.config.CompressionConfig;
import com.automation.config.DashboardReportAssistConfig;
import com.automation.config.FactoryConfig;
import com.automation.config.SchedulerConfig;
import com.automation.config.SummarizationConfig;
import com.automation.config.WishConfig;
import com.automation.service.ai.catalog.ModelCatalogService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Einstellungen einzelner Funktionen: Scheduler-Nachholen, Zusammenfassung, Factory, Wuensche, Modellkatalog, Kompression, Dashboard-Assistent. */
@Component
@Order(4)
public class FeatureSettings implements RuntimeSettingsGroup {

    private final SchedulerConfig schedulerConfig;
    private final SummarizationConfig summarizationConfig;
    private final FactoryConfig factoryConfig;
    private final WishConfig wishConfig;
    private final ModelCatalogService modelCatalogService;
    private final CompressionConfig compressionConfig;
    private final DashboardReportAssistConfig dashboardReportAssistConfig;

    public FeatureSettings(SchedulerConfig schedulerConfig, SummarizationConfig summarizationConfig,
                           FactoryConfig factoryConfig, WishConfig wishConfig, ModelCatalogService modelCatalogService,
                           CompressionConfig compressionConfig, DashboardReportAssistConfig dashboardReportAssistConfig) {
        this.schedulerConfig = schedulerConfig;
        this.summarizationConfig = summarizationConfig;
        this.factoryConfig = factoryConfig;
        this.wishConfig = wishConfig;
        this.modelCatalogService = modelCatalogService;
        this.compressionConfig = compressionConfig;
        this.dashboardReportAssistConfig = dashboardReportAssistConfig;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> m = new LinkedHashMap<>();
        m.put("scheduler-catch-up-on-resume", v -> schedulerConfig.setCatchUpOnResume(Integer.parseInt(v) == 1));
        m.put("scheduler-resume-grace-minutes", v -> schedulerConfig.setResumeGraceMinutes(Long.parseLong(v)));
        m.put("summarization-timeout-seconds", v -> summarizationConfig.setTimeoutSeconds(Integer.parseInt(v)));
        m.put("factory-timeout-seconds", v -> factoryConfig.setTimeoutSeconds(Integer.parseInt(v)));
        m.put("wish-total-timeout-minutes", v -> wishConfig.setTotalTimeoutMinutes(Integer.parseInt(v)));
        m.put("wish-workflow-wait-minutes", v -> wishConfig.setWorkflowWaitMinutes(Integer.parseInt(v)));
        m.put("wish-default-shell-timeout-seconds", v -> wishConfig.setDefaultShellTimeoutSeconds(Integer.parseInt(v)));
        m.put("wish-default-agentic-total-timeout-seconds", v -> wishConfig.setDefaultAgenticTotalTimeoutSeconds(Integer.parseInt(v)));
        m.put("model-catalog-ttl-seconds", v -> modelCatalogService.setTtlSeconds(Integer.parseInt(v)));
        m.put("compress-ai-max-input-chars", v -> compressionConfig.setMaxAiInputChars(Integer.parseInt(v)));
        m.put("dashboard-report-assist-enabled", v -> dashboardReportAssistConfig.setEnabled(Integer.parseInt(v) == 1));
        return m;
    }

    @Override
    public void currentValues(Map<String, Object> out) {
        out.put("schedulerCatchUpOnResume", schedulerConfig.isCatchUpOnResume() ? 1 : 0);
        out.put("schedulerResumeGraceMinutes", schedulerConfig.getResumeGraceMinutes());
        out.put("summarizationTimeoutSeconds", summarizationConfig.getTimeoutSeconds());
        out.put("factoryTimeoutSeconds", factoryConfig.getTimeoutSeconds());
        out.put("wishTotalTimeoutMinutes", wishConfig.getTotalTimeoutMinutes());
        out.put("wishWorkflowWaitMinutes", wishConfig.getWorkflowWaitMinutes());
        out.put("wishDefaultShellTimeoutSeconds", wishConfig.getDefaultShellTimeoutSeconds());
        out.put("wishDefaultAgenticTotalTimeoutSeconds", wishConfig.getDefaultAgenticTotalTimeoutSeconds());
        out.put("modelCatalogTtlSeconds", modelCatalogService.getTtlSeconds());
        out.put("compressAiMaxInputChars", compressionConfig.getMaxAiInputChars());
        out.put("dashboardReportAssistEnabled", dashboardReportAssistConfig.isEnabled() ? 1 : 0);
    }
}
