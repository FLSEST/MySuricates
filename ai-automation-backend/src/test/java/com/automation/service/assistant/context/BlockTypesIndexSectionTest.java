package com.automation.service.assistant.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlockTypesIndexSectionTest {

    private BlockTypesIndexSection section;

    @BeforeEach
    void setUp() {
        section = new BlockTypesIndexSection();
    }

    @Test
    void compact_showsCount() {
        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString())
                .contains("**Block-Typen:** ")
                .contains(" verfügbar");
    }

    @Test
    void full_showsAllBlockTypes() {
        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        String out = sb.toString();
        assertThat(out)
                .contains("**Block-Typen (")
                .contains("[simple]")
                .contains("[shell]")
                .contains("[agentic]")
                .contains("[conditional]")
                .contains("[foreach]")
                .contains("[while]")
                .contains("[switch]")
                .contains("[retry]")
                .contains("[approval]")
                .contains("[sub-workflow]")
                .contains("[skill-capability]")
                .contains("[human-input]")
                .contains("[integration]")
                .contains("[document]")
                .contains("<fetch type=\"block-spec\"");
    }

    @Test
    void blockTypesList_isNotEmpty() {
        assertThat(BlockTypesIndexSection.BLOCK_TYPES).isNotEmpty();
        assertThat(BlockTypesIndexSection.BLOCK_TYPES).extracting(BlockTypesIndexSection.BlockTypeEntry::type)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
    }
}
