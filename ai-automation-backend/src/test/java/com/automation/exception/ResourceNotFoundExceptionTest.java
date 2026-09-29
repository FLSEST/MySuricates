package com.automation.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceNotFoundExceptionTest {

    @Test
    void should_formatMessage_when_constructedWithTypicalResourceTypeAndId() {
        var ex = new ResourceNotFoundException("Workflow", "abc-123");

        assertThat(ex.getMessage()).isEqualTo("Workflow not found with id: abc-123");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_formatMessage_when_constructedWithSpecialCharactersAndCompositeId() {
        var ex = new ResourceNotFoundException("Sub-Workflow", "proj/42:rev-3 (draft)");

        assertThat(ex.getMessage()).isEqualTo("Sub-Workflow not found with id: proj/42:rev-3 (draft)");
    }

    @Test
    void should_formatMessage_when_constructedWithEmptyValues() {
        var ex = new ResourceNotFoundException("", "");

        assertThat(ex.getMessage()).isEqualTo(" not found with id: ");
    }

    @Test
    void should_carryResourceAndId_asParamsForTheUi() {
        var ex = new ResourceNotFoundException("Workflow", "abc-123");

        assertThat(ex.getParams()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("resource", "Workflow", "id", "abc-123"));
    }

    @Test
    void should_writeNullIdAsText_insteadOfFailing() {
        var ex = new ResourceNotFoundException("Workflow", null);

        assertThat(ex.getParams()).containsEntry("id", "null");
    }
}
