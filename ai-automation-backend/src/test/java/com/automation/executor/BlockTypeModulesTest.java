package com.automation.executor;

import com.automation.modules.ModuleCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockTypeModulesTest {

    @Test
    void moduleBoundTypes_nameTheirModule() {
        assertThat(BlockTypeModules.requiredFor("skill-capability")).contains(ModuleCatalog.SKILLS);
        assertThat(BlockTypeModules.requiredFor("dashboard-report")).contains(ModuleCatalog.DASHBOARD_REPORT);
    }

    @Test
    void coreTypesUnknownTypesAndNull_needNoModule() {
        assertThat(BlockTypeModules.requiredFor("shell")).isEmpty();
        assertThat(BlockTypeModules.requiredFor("no-such-type")).isEmpty();
        assertThat(BlockTypeModules.requiredFor(null)).isEmpty();
    }

    @Test
    void all_isTheSameImmutableMapping() {
        assertThat(BlockTypeModules.all()).hasSize(2)
                .allSatisfy((type, module) -> assertThat(BlockTypeModules.requiredFor(type)).contains(module));
        assertThatThrownBy(() -> BlockTypeModules.all().put("shell", ModuleCatalog.SKILLS))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
