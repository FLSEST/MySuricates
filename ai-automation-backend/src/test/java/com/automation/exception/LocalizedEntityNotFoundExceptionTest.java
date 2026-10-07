package com.automation.exception;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalizedEntityNotFoundExceptionTest {

    @Test
    void should_remainAnEntityNotFoundException_forExistingCallers() {
        var ex = new LocalizedEntityNotFoundException("Workflow", "abc");

        assertThat(ex).isInstanceOf(EntityNotFoundException.class).isInstanceOf(LocalizedError.class);
    }

    @Test
    void should_formatNamedNotFoundMessage_withResourceAndId() {
        var ex = new LocalizedEntityNotFoundException("Workflow", "abc-123");

        assertThat(ex.getMessage()).isEqualTo("Workflow not found: abc-123");
        assertThat(ex.errorMessage()).isEqualTo(ErrorMessage.NAMED_NOT_FOUND);
        assertThat(ex.errorMessage().getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(ex.params()).containsExactlyInAnyOrderEntriesOf(Map.of("resource", "Workflow", "id", "abc-123"));
    }

    @Test
    void should_writeNullValuesAsText_insteadOfFailing() {
        var ex = new LocalizedEntityNotFoundException(null, null);

        assertThat(ex.params()).containsEntry("resource", "null").containsEntry("id", "null");
        assertThat(ex.getMessage()).isEqualTo("null not found: null");
    }
}
