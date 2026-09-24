package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.modules.ModuleCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockTypeModulesTest {

    private static BlockDefinition block(String type, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).config(new HashMap<>(config)).build();
    }

    @Test
    void moduleBoundTypes_nameTheirModule() {
        assertThat(BlockTypeModules.requiredFor("skill-capability")).contains(ModuleCatalog.SKILLS);
        assertThat(BlockTypeModules.requiredFor("dashboard-report")).contains(ModuleCatalog.DASHBOARD_REPORT);
        assertThat(BlockTypeModules.requiredFor("integration")).contains(ModuleCatalog.INTEGRATIONS);
    }

    @Test
    void coreTypesUnknownTypesAndNull_needNoModule() {
        assertThat(BlockTypeModules.requiredFor("shell")).isEmpty();
        assertThat(BlockTypeModules.requiredFor("no-such-type")).isEmpty();
        assertThat(BlockTypeModules.requiredFor(null)).isEmpty();
    }

    @Test
    void all_isTheSameImmutableMapping() {
        assertThat(BlockTypeModules.all()).hasSize(3)
                .allSatisfy((type, module) -> assertThat(BlockTypeModules.requiredFor(type)).contains(module));
        assertThatThrownBy(() -> BlockTypeModules.all().put("shell", ModuleCatalog.SKILLS))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiredBy_collectsTopLevelAndNestedBlocks_onceEach_inCatalogOrder() {
        var blocks = List.of(
                block("skill-capability", Map.of("capability", "x")),
                block("shell", Map.of("command", "date")),
                block("switch", Map.of("expression", "a", "cases", List.of(Map.of("value", "a", "blocks",
                        List.of(Map.of("type", "integration", "config", Map.of("provider", "github"))))))),
                block("dashboard-report", Map.of("status", "GREEN")),
                block("skill-capability", Map.of("capability", "y")));

        assertThat(BlockTypeModules.requiredBy(blocks)).containsExactly(
                ModuleCatalog.DASHBOARD_REPORT, ModuleCatalog.INTEGRATIONS, ModuleCatalog.SKILLS);
    }

    @Test
    void requiredBy_isEmptyForCoreBlocksNullAndGaps() {
        List<BlockDefinition> withGap = new ArrayList<>();
        withGap.add(null);
        withGap.add(block("shell", Map.of("command", "date")));

        assertThat(BlockTypeModules.requiredBy(withGap)).isEmpty();
        assertThat(BlockTypeModules.requiredBy(null)).isEmpty();
    }
}
