package com.automation.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityNotFoundExceptionTest {

    @Test
    void should_formatMessage_when_constructedWithNormalSkillAndCapability() {
        var ex = new CapabilityNotFoundException("skill-abc-123", "cap-xyz-456");

        assertThat(ex.getMessage()).isEqualTo("Capability not found with id: cap-xyz-456 in skill skill-abc-123");
        assertThat(ex).isInstanceOf(ResourceNotFoundException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_formatMessage_when_constructedWithSpecialCharactersAndCompositeNames() {
        var ex = new CapabilityNotFoundException("proj/42:skill-3 (draft)", "ns::action-β [v2]");

        assertThat(ex.getMessage()).isEqualTo("Capability not found with id: ns::action-β [v2] in skill proj/42:skill-3 (draft)");
    }
}
