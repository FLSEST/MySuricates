package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockNameRulesTest {

    private static BlockDefinition block(String name) {
        BlockDefinition b = new BlockDefinition();
        b.setType("shell");
        b.setName(name);
        return b;
    }

    @Test
    void rejects_previousOutput_asBlockName() {
        assertThatThrownBy(() -> BlockNameRules.rejectReservedNames(List.of(block("lint"), block("previous_output"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("previous_output")
                .hasMessageContaining("reserved");
    }

    @Test
    void rejects_paddedReservedName() {
        assertThatThrownBy(() -> BlockNameRules.rejectReservedNames(List.of(block("  previous_output "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accepts_ordinaryNames_nullNames_andNullList() {
        assertThatCode(() -> BlockNameRules.rejectReservedNames(Arrays.asList(block("build"), block(null), null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> BlockNameRules.rejectReservedNames(null)).doesNotThrowAnyException();
        assertThatCode(() -> BlockNameRules.rejectReservedNames(List.of(block("previous_output_2"))))
                .doesNotThrowAnyException();
    }
}
