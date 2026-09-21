package com.automation.service.export;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportedWorkflowTest {

    @Test
    void wertSemantikUndReihenfolgeDerBloecke() {
        BlockDefinition a = new BlockDefinition();
        a.setType("shell");
        BlockDefinition b = new BlockDefinition();
        b.setType("simple");

        ImportedWorkflow w = new ImportedWorkflow("Name", List.of(a, b));

        assertThat(w.name()).isEqualTo("Name");
        assertThat(w.blocks()).containsExactly(a, b);
        assertThat(w).isEqualTo(new ImportedWorkflow("Name", List.of(a, b)))
                .hasSameHashCodeAs(new ImportedWorkflow("Name", List.of(a, b)))
                .isNotEqualTo(new ImportedWorkflow("Name", List.of(b, a)));
    }

    @Test
    void leererImportIstGueltig() {
        ImportedWorkflow w = new ImportedWorkflow(null, List.of());
        assertThat(w.name()).isNull();
        assertThat(w.blocks()).isEmpty();
    }
}
