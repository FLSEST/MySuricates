package com.automation.architecture;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.BlockTypeModules;
import com.automation.executor.ExecutionContext;
import com.automation.executor.blocks.DashboardReportBlockExecutor;
import com.automation.executor.blocks.IntegrationBlockExecutor;
import com.automation.executor.blocks.SkillCapabilityBlockExecutor;
import com.automation.exception.WorkflowExecutionException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.assistant.context.BlockTypesIndexSection;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * A block type that belongs to an optional module has to be gated on BOTH sides, or the editor
 * offers a block this instance cannot run: the executor refuses, and
 * {@code GET /system/capabilities} does not offer the type. The template picker hides templates
 * with such a block while the module is off (TODO 560d18dd).
 *
 * <p>{@link BlockTypeModules} is the single list for both sides. Every entry needs a case here —
 * {@link #everyGatedTypeHasARefusalCaseInThisTest} fails when someone adds one without extending
 * this file. The other direction is pinned too: an executor that checks a module must be listed
 * ({@code integration} once refused on core while the editor still offered it).
 */
class BlockTypeModuleGateTest {

    /** A module check in an executor's source: {@code isEnabled(ModuleCatalog.X)}. */
    private static final Pattern MODULE_CHECK =
            Pattern.compile("isEnabled\\(\\s*(?:ModuleCatalog\\.)?([A-Z][A-Z_]+)\\s*\\)");

    private static ModuleRegistry withOff(ModuleCatalog module) {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("preset", "full");
        modules.put(module.key(), "false");
        return ModuleRegistry.of(modules, Map.of());
    }

    private static ExecutionContext context() {
        return ExecutionContext.builder().containerId("c-1").workflowRunId("run-1")
                .variables(new HashMap<>()).build();
    }

    @Test
    void everyGatedTypeIsAKnownBlockType() {
        assertThat(BlockTypeModules.all().keySet())
                .allSatisfy(type -> assertThat(BlockTypesIndexSection.BLOCK_TYPES)
                        .as("'%s' is gated but is not a registered block type", type)
                        .anySatisfy(entry -> assertThat(entry.type()).isEqualTo(type)));
    }

    @Test
    void dashboardReportExecutorRefusesWhenItsModuleIsOff() {
        var executor = new DashboardReportBlockExecutor(withOff(ModuleCatalog.DASHBOARD_REPORT));
        var block = BlockDefinition.builder().type("dashboard-report")
                .config(Map.of("status", "GREEN")).build();

        assertThatThrownBy(() -> executor.execute(block, context()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining(ModuleCatalog.DASHBOARD_REPORT.key());
    }

    @Test
    void skillCapabilityExecutorRefusesWhenItsModuleIsOff() {
        var executor = new SkillCapabilityBlockExecutor(
                mock(com.automation.service.cli.CommandExecutorService.class),
                mock(com.automation.service.skill.SkillService.class),
                withOff(ModuleCatalog.SKILLS));
        var block = BlockDefinition.builder().type("skill-capability")
                .config(Map.of("capability", "anything")).build();

        assertThatThrownBy(() -> executor.execute(block, context()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining(ModuleCatalog.SKILLS.key());
    }

    /** Refuses with a failed step instead of an exception — the run still stops before any call. */
    @Test
    void integrationExecutorRefusesWhenItsModuleIsOff() {
        IntegrationRegistry registry = mock(IntegrationRegistry.class);
        var executor = new IntegrationBlockExecutor(registry, mock(IntegrationOAuthService.class),
                new ObjectMapper(), withOff(ModuleCatalog.INTEGRATIONS));
        var block = BlockDefinition.builder().type("integration")
                .config(Map.of("provider", "github", "action", "list_issues")).build();

        var step = executor.execute(block, context());

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains(ModuleCatalog.INTEGRATIONS.key());
        Mockito.verifyNoInteractions(registry);
    }

    /** Forces a new entry in BlockTypeModules to get its own refusal case above. */
    @Test
    void everyGatedTypeHasARefusalCaseInThisTest() {
        assertThat(BlockTypeModules.all())
                .containsOnlyKeys("skill-capability", "dashboard-report", "integration");
    }

    /**
     * The other direction: an executor that checks a module refuses its block while the module is
     * off, so {@link BlockTypeModules} must list its types — otherwise capabilities and the template
     * picker offer a block the run then refuses. Read from the executor sources, because the check
     * is a line of code, not a declaration.
     */
    @Test
    void everyExecutorThatChecksAModuleIsInTheList() throws IOException {
        Map<String, ModuleCatalog> checkedByExecutors = new TreeMap<>();
        List<String> scanned = new ArrayList<>();
        for (Class<?> executorClass : executorClasses()) {
            String source = Files.readString(sourceOf(executorClass));
            scanned.add(executorClass.getSimpleName());
            Matcher check = MODULE_CHECK.matcher(source);
            while (check.find()) {
                ModuleCatalog module = ModuleCatalog.valueOf(check.group(1));
                BlockExecutor probe = (BlockExecutor) Mockito.mock(executorClass,
                        withSettings().defaultAnswer(CALLS_REAL_METHODS));
                probe.supportedTypes().forEach(type -> checkedByExecutors.put(type, module));
            }
        }

        assertThat(scanned).as("executor sources read").contains("IntegrationBlockExecutor", "ShellBlockExecutor");
        assertThat(checkedByExecutors)
                .as("block types whose executor checks a module (left) vs. BlockTypeModules (right)")
                .isEqualTo(new TreeMap<>(BlockTypeModules.all()));
    }

    @Test
    void anUngatedTypeNeedsNoModule() {
        assertThat(BlockTypeModules.requiredFor("shell")).isEmpty();
        assertThat(BlockTypeModules.requiredFor(null)).isEmpty();
        assertThat(BlockTypeModules.requiredFor("dashboard-report"))
                .contains(ModuleCatalog.DASHBOARD_REPORT);
    }

    private static List<Class<?>> executorClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BlockExecutor.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.automation.executor")) {
            try {
                classes.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return classes;
    }

    /** Works from the module and from the repository root, like {@link ShippedDefinitions}. */
    private static Path sourceOf(Class<?> type) {
        String relative = type.getName().replace('.', '/') + ".java";
        for (Path root : List.of(Paths.get("src/main/java"), Paths.get("ai-automation-backend/src/main/java"))) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("source of " + type.getName() + " not found from "
                + Paths.get("").toAbsolutePath());
    }
}
