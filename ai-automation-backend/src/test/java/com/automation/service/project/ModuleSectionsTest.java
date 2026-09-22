package com.automation.service.project;

import com.automation.modules.ModuleCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleSectionsTest {

    private static final String TEXT = """
            - Kern
            <!-- module: chains -->
            - Kette
            <!-- /module -->
            - danach

            <!-- module: webhooks -->

            ### Webhooks

            - Aufruf
            <!-- /module -->
            """;

    @Test
    void aBlockOfADisabledModuleIsRemoved_andEnabledOnesLoseOnlyTheirMarkers() {
        String rendered = ModuleSections.apply(TEXT, module -> module == ModuleCatalog.CHAINS);

        assertThat(rendered).isEqualTo("- Kern\n- Kette\n- danach\n\n");
    }

    @Test
    void allModulesOn_keepsEveryBlockWithoutMarkers() {
        String rendered = ModuleSections.apply(TEXT, module -> true);

        assertThat(rendered).contains("- Kette").contains("### Webhooks").doesNotContain("<!--");
    }

    @Test
    void anUnknownModuleCountsAsOff() {
        assertThat(ModuleSections.apply("a\n<!-- module: no-such-module -->\nb\n<!-- /module -->\nc\n", module -> true))
                .isEqualTo("a\nc\n");
    }

    @Test
    void textWithoutBlocksIsUnchanged() {
        assertThat(ModuleSections.apply("plain", module -> false)).isEqualTo("plain");
        assertThat(ModuleSections.apply(null, module -> false)).isNull();
    }
}
