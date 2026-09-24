package com.automation.service;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleConditions;
import com.automation.modules.ModuleStateChangedEvent;
import com.automation.service.assistant.AssistantActionSuggestionService;
import com.automation.service.system.AppSettingChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the cached settings of the assistant module current: chat model, suggestions model and the
 * suggestions switch. One listener for the module instead of one per service, so the module condition
 * and the catch-up below live in one place.
 *
 * <p>While the module is off, setting changes are skipped. The settings page still offers these keys
 * then, and every cache was loaded at startup — so switching the module on at runtime re-reads all of
 * them, otherwise it would run with the values from before the change until the next restart.
 */
@Component
@RequiredArgsConstructor
public class AssistantSettingsListener {

    private final AssistantService assistantService;
    private final PageSuggestionService pageSuggestionService;
    private final AssistantActionSuggestionService actionSuggestionService;

    @EventListener(condition = ModuleConditions.ASSISTANT)
    public void onSettingChanged(AppSettingChangedEvent event) {
        switch (event.key()) {
            case "assistant-model" -> assistantService.refreshCachedModel();
            case "suggestions-model" -> {
                pageSuggestionService.refreshCachedModel();
                actionSuggestionService.refreshCache();
            }
            case "suggestions-enabled" -> actionSuggestionService.refreshCache();
            default -> {
                // not an assistant setting
            }
        }
    }

    @EventListener
    public void onModuleStateChanged(ModuleStateChangedEvent event) {
        if (event.getModule() == ModuleCatalog.ASSISTANT && event.isEnabled()) {
            assistantService.refreshCachedModel();
            pageSuggestionService.refreshCachedModel();
            actionSuggestionService.refreshCache();
        }
    }
}
