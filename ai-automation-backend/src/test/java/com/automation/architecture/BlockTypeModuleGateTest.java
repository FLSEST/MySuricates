package com.automation.architecture;

import com.automation.entity.BlockDefinition;
import com.automation.executor.BlockTypeModules;
import com.automation.executor.ExecutionContext;
import com.automation.executor.blocks.DashboardReportBlockExecutor;
import com.automation.executor.blocks.SkillCapabilityBlockExecutor;
import com.automation.exception.WorkflowExecutionException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.assistant.context.BlockTypesIndexSection;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * A block type that belongs to an optional module has to be gated on BOTH sides, or the editor
 * offers a block this instance cannot run: the executor refuses, and
 * {@code GET /system/capabilities} does not offer the type.
 *
 * <p>{@link BlockTypeModules} is the single list for both sides. Every entry needs a case here —
 * the last test fails when someone adds one without extending this file.
 */
class BlockTypeModuleGateTest {

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

    /** Forces a new entry in BlockTypeModules to get its own refusal case above. */
    @Test
    void everyGatedTypeHasARefusalCaseInThisTest() {
        assertThat(BlockTypeModules.all())
                .containsOnlyKeys("skill-capability", "dashboard-report");
    }

    @Test
    void anUngatedTypeNeedsNoModule() {
        assertThat(BlockTypeModules.requiredFor("shell")).isEmpty();
        assertThat(BlockTypeModules.requiredFor(null)).isEmpty();
        assertThat(BlockTypeModules.requiredFor("dashboard-report"))
                .contains(ModuleCatalog.DASHBOARD_REPORT);
    }
}
