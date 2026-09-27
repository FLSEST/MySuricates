package com.automation.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TODO 40087be9: the confirmation-card label always carries a non-null, immutable parameter map. */
class PendingToolLabelTest {

    @Test
    void of_onlyFallbackSentence() {
        PendingToolLabel label = PendingToolLabel.of("Delete workflow 76662293");

        assertThat(label.description()).isEqualTo("Delete workflow 76662293");
        assertThat(label.subject()).isNull();
        assertThat(label.params()).isEmpty();
    }

    @Test
    void nullParams_becomeEmptyMap() {
        PendingToolLabel label = new PendingToolLabel("Create TODO", "Call Anna", null);

        assertThat(label.params()).isNotNull().isEmpty();
        assertThat(label.subject()).isEqualTo("Call Anna");
    }

    @Test
    void params_areAnImmutableCopy() {
        Map<String, String> source = new HashMap<>(Map.of("priority", "HIGH"));
        PendingToolLabel label = new PendingToolLabel("Create TODO", "Call Anna", source);

        source.put("status", "DONE");

        assertThat(label.params()).containsExactlyEntriesOf(Map.of("priority", "HIGH"));
        assertThatThrownBy(() -> label.params().put("status", "DONE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
