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
            "DeadLetterController",
            "DocsController",
            "ExecutionController",
            "NotificationController",
            "ProjectController",
            "ScheduleController",
            "SearchController",
            "SystemAccessController",
            "SystemAiProviderController",
            "SystemController",
            "SystemMaintenanceController",
            "SystemSettingsController",
            "TodoController",
            "TodoTriggerController",
            "WorkflowController",
            "WorkflowPoolController",
            "WorkflowTemplateController");

    /** Method-level assignments inside core controllers — pinned so they cannot be dropped unnoticed. */
    private static final Map<String, ModuleCatalog> METHOD_MODULES = Map.of(
            "TodoController#exportTodo", ModuleCatalog.INTEGRATIONS,
            "WorkflowController#exportWorkflowAsBpmn", ModuleCatalog.BPMN,
            "WorkflowController#repairWorkflow", ModuleCatalog.FACTORY,
            "WorkflowController#activateSkills", ModuleCatalog.SKILLS,
            "WorkflowController#deactivateSkill", ModuleCatalog.SKILLS,
            "SystemAccessController#enableRemoteAccess", ModuleCatalog.REMOTE_ACCESS,
            "SystemAccessController#disableRemoteAccess", ModuleCatalog.REMOTE_ACCESS,
            "SystemAccessController#remoteAccessStatus", ModuleCatalog.REMOTE_ACCESS);

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
