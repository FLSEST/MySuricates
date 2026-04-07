package com.automation.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillNotFoundExceptionTest {

    @Test
    void should_formatMessage_when_constructedWithNormalSkillId() {
        var ex = new SkillNotFoundException("skill-abc-123");

        assertThat(ex.getMessage()).isEqualTo("Skill not found with id: skill-abc-123");
        assertThat(ex).isInstanceOf(ResourceNotFoundException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_formatMessage_when_constructedWithSpecialCharactersAndCompositeId() {
        var ex = new SkillNotFoundException("proj/42:skill-3 (draft)");

        assertThat(ex.getMessage()).isEqualTo("Skill not found with id: proj/42:skill-3 (draft)");
    }
}
