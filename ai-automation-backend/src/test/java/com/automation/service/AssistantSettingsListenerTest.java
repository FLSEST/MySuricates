package com.automation.service;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleConditions;
import com.automation.modules.ModuleRegistry;
import com.automation.modules.ModuleStateChangedEvent;
import com.automation.service.assistant.AssistantActionSuggestionService;
import com.automation.service.system.AppSettingChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Same key → refresh mapping as the direct calls in SystemSettingsService had, gated by the module. */
class AssistantSettingsListenerTest {

    private final AssistantService assistantService = mock(AssistantService.class);
    private final PageSuggestionService pageSuggestionService = mock(PageSuggestionService.class);
    private final AssistantActionSuggestionService actionSuggestionService = mock(AssistantActionSuggestionService.class);

    private final AssistantSettingsListener listener =
            new AssistantSettingsListener(assistantService, pageSuggestionService, actionSuggestionService);

    @Test
    void assistantModel_refreshesTheChatModel() {
        listener.onSettingChanged(new AppSettingChangedEvent("assistant-model"));

        verify(assistantService).refreshCachedModel();
        verifyNoInteractions(pageSuggestionService, actionSuggestionService);
    }

    @Test
    void suggestionsModel_refreshesPageAndActionSuggestions() {
        listener.onSettingChanged(new AppSettingChangedEvent("suggestions-model"));

        verify(pageSuggestionService).refreshCachedModel();
        verify(actionSuggestionService).refreshCache();
        verifyNoInteractions(assistantService);
    }

    @Test
    void suggestionsEnabled_refreshesActionSuggestionsOnly() {
        listener.onSettingChanged(new AppSettingChangedEvent("suggestions-enabled"));

        verify(actionSuggestionService).refreshCache();
        verifyNoInteractions(assistantService, pageSuggestionService);
    }

    @Test
    void otherKey_refreshesNothing() {
        listener.onSettingChanged(new AppSettingChangedEvent("default-ai-provider"));
        listener.onSettingChanged(new AppSettingChangedEvent("auto-repair-default"));

        verifyNoInteractions(assistantService, pageSuggestionService, actionSuggestionService);
    }

    @Test
    void switchingTheModuleOn_catchesUpOnEveryCache() {
        listener.onModuleStateChanged(new ModuleStateChangedEvent(this, ModuleCatalog.ASSISTANT, true));

        verify(assistantService).refreshCachedModel();
        verify(pageSuggestionService).refreshCachedModel();
        verify(actionSuggestionService).refreshCache();
    }

    @Test
    void switchingTheModuleOffOrAnotherModule_refreshesNothing() {
        listener.onModuleStateChanged(new ModuleStateChangedEvent(this, ModuleCatalog.ASSISTANT, false));
        listener.onModuleStateChanged(new ModuleStateChangedEvent(this, ModuleCatalog.CHAINS, true));

        verifyNoInteractions(assistantService, pageSuggestionService, actionSuggestionService);
    }

    @Test
    void settingListenerUsesTheAssistantCondition() throws NoSuchMethodException {
        EventListener annotation = AssistantSettingsListener.class
                .getMethod("onSettingChanged", AppSettingChangedEvent.class)
                .getAnnotation(EventListener.class);

        assertThat(annotation.condition()).isEqualTo(ModuleConditions.ASSISTANT);
    }

    @Test
    void publishedChange_reachesTheListenerWhileTheModuleIsOn() {
        publishWith(Map.of("assistant", "true"), new AppSettingChangedEvent("suggestions-model"));

        verify(pageSuggestionService).refreshCachedModel();
        verify(actionSuggestionService).refreshCache();
    }

    @Test
    void publishedChange_isSkippedWhileTheModuleIsOff() {
        publishWith(Map.of(), new AppSettingChangedEvent("suggestions-model"));

        verifyNoInteractions(assistantService, pageSuggestionService, actionSuggestionService);
    }

    /** Real event dispatch, so the SpEL condition on the listener is evaluated against the registry. */
    private void publishWith(Map<String, String> modules, Object event) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("moduleRegistry", ModuleRegistry.class, () -> ModuleRegistry.of(modules, Map.of()));
            ctx.registerBean(AssistantSettingsListener.class, () -> listener);
            ctx.refresh();
            ctx.publishEvent(event);
        }
    }
}
