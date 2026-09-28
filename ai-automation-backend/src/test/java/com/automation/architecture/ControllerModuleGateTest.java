package com.automation.architecture;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate: every controller is either assigned to an optional module via {@link RequiresModule}
 * or explicitly listed as core. A new controller cannot silently bypass the module system —
 * add it to the catalog or consciously to {@link #CORE_CONTROLLERS}.
 */
class ControllerModuleGateTest {

    /** Always-on platform core. Method-level module annotations are allowed inside these. */
    private static final Set<String> CORE_CONTROLLERS = Set.of(
            "ActivityLogController",
            "CompletionTriggerController",
            "DashboardLayoutController",
            "DashboardWorkflowController",
            "DeadLetterController",
            "DocsController",
            "GoalController",
            "GoalWorkflowController",
            "ExecutionController",
            "LearningController",
            "NotificationController",
            "ProjectController",
            "ScheduleController",
            "SearchController",
            "SystemAccessController",
            "SystemAiProviderController",
            "SystemController",
            "SystemMaintenanceController",
            "SystemModulesController",
            // How a slow platform gets diagnosed — must not switch off with a feature module.
            "SystemRequestMetricsController",
            "SystemSettingsController",
            "TodoController",
            "TodoTriggerController",
            // Live logs, assistant and the run terminal all handshake through /ws — the ticket
            // endpoint that authenticates them is core, even though the terminal itself is a module.
            "WebSocketTicketController",
            "WorkflowController",
            // Wie schlechte Workflows gefunden werden — dasselbe Argument wie bei den
            // Request-Metriken: ein Diagnosewerkzeug darf nicht mit einem Feature-Modul
            // verschwinden. Er meldet nur und greift in keinen Schreibweg ein (182bd224).
            "WorkflowAnalyzerController",
            // Aus WorkflowController herausgelöst (6c2a2ad7) — gleiche Einordnung wie dort
            "WorkflowAssistController",
            "WorkflowPoolController",
            // Siegelstatus lesen: ein Siegel bleibt wirksam, wenn der Schalter aus ist — also sichtbar
            "WorkflowSealStatusController",
            "WorkflowTemplateController",
            "WorkflowVersionController");

    /** Method-level assignments inside core controllers — pinned so they cannot be dropped unnoticed. */
    private static final Map<String, ModuleCatalog> METHOD_MODULES = Map.ofEntries(
            Map.entry("TodoController#exportTodo", ModuleCatalog.INTEGRATIONS),
            Map.entry("WorkflowController#exportWorkflowAsBpmn", ModuleCatalog.BPMN),
            Map.entry("WorkflowAssistController#repairWorkflow", ModuleCatalog.FACTORY),
            Map.entry("WorkflowController#activateSkills", ModuleCatalog.SKILLS),
            Map.entry("WorkflowController#deactivateSkill", ModuleCatalog.SKILLS),
            Map.entry("SystemAccessController#enableRemoteAccess", ModuleCatalog.REMOTE_ACCESS),
            // Onboarding files in projects belong to the onboarding-profiles module (TODO 644fd5ad).
            Map.entry("ProjectController#enableOnboarding", ModuleCatalog.ONBOARDING_PROFILES),
            Map.entry("ProjectController#disableOnboarding", ModuleCatalog.ONBOARDING_PROFILES),
            Map.entry("ProjectController#refreshOnboarding", ModuleCatalog.ONBOARDING_PROFILES),
            Map.entry("ProjectController#getOnboardingStatus", ModuleCatalog.ONBOARDING_PROFILES),
            Map.entry("ProjectController#getOnboardingPreview", ModuleCatalog.ONBOARDING_PROFILES),
            Map.entry("SystemAccessController#integrationSnippet", ModuleCatalog.ONBOARDING_PROFILES),
            Map.entry("SystemAccessController#disableRemoteAccess", ModuleCatalog.REMOTE_ACCESS),
            Map.entry("SystemAccessController#remoteAccessStatus", ModuleCatalog.REMOTE_ACCESS),
            Map.entry("SystemMaintenanceController#repairAllFailed", ModuleCatalog.FACTORY),
            Map.entry("WebhookController#generateSigningSecret", ModuleCatalog.WEBHOOK_SIGNATURES),
            Map.entry("WebhookController#removeSigningSecret", ModuleCatalog.WEBHOOK_SIGNATURES),
            // Dashboard, platform stats and run rating stay core — the core dashboard reads them
            Map.entry("LearningController#getWorkflowStats", ModuleCatalog.LEARNING),
            Map.entry("LearningController#getStepAnalytics", ModuleCatalog.LEARNING),
            Map.entry("LearningController#getOptimizationSuggestions", ModuleCatalog.LEARNING),
            Map.entry("LearningController#getSimilarWorkflows", ModuleCatalog.LEARNING),
            Map.entry("LearningController#getAgenticStrategyHistory", ModuleCatalog.LEARNING),
            Map.entry("LearningController#getAgenticAnalytics", ModuleCatalog.LEARNING),
            Map.entry("LearningController#getToolPatterns", ModuleCatalog.LEARNING),
            Map.entry("LearningController#getExecutionInsights", ModuleCatalog.LEARNING),
            Map.entry("LearningController#getRunInsights", ModuleCatalog.LEARNING));

    @Test
    void everyControllerIsCoreOrAssignedToAModule() {
        Set<Class<?>> controllers = controllers();
        assertThat(controllers).as("controller scan found nothing — package moved?").hasSizeGreaterThan(20);

        Set<String> unassigned = new TreeSet<>();
        Set<String> doubleAssigned = new TreeSet<>();
        for (Class<?> controller : controllers) {
            boolean annotated = controller.isAnnotationPresent(RequiresModule.class);
            boolean core = CORE_CONTROLLERS.contains(controller.getSimpleName());
            if (!annotated && !core) {
                unassigned.add(controller.getSimpleName());
            }
            if (annotated && core) {
                doubleAssigned.add(controller.getSimpleName());
            }
        }

        assertThat(unassigned)
                .as("Controllers without @RequiresModule that are not on the core allowlist")
                .isEmpty();
        assertThat(doubleAssigned)
                .as("Controllers on the core allowlist must not carry a class-level @RequiresModule")
                .isEmpty();
    }

    @Test
    void coreAllowlistHasNoStaleEntries() {
        Set<String> existing = controllers().stream().map(Class::getSimpleName).collect(Collectors.toSet());

        assertThat(CORE_CONTROLLERS).allSatisfy(name -> assertThat(existing).as(name).contains(name));
    }

    @Test
    void methodLevelAssignmentsMatchThePinnedList() {
        Map<String, ModuleCatalog> found = controllers().stream()
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> m.isAnnotationPresent(RequiresModule.class))
                .collect(Collectors.toMap(ControllerModuleGateTest::methodKey,
                        m -> m.getAnnotation(RequiresModule.class).value()));

        assertThat(found).isEqualTo(METHOD_MODULES);
    }

    private static String methodKey(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    private static Set<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class)); // also matches @RestController
        return scanner.findCandidateComponents("com.automation.controller").stream()
                .map(BeanDefinition::getBeanClassName)
                .map(ControllerModuleGateTest::load)
                .collect(Collectors.toSet());
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
