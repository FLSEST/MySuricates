package com.automation.exception;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

class ElementNotFoundExceptionTest {

    @Test
    void should_remainANoSuchElementException_forExistingCallers() {
        var ex = new ElementNotFoundException("Chain", "c1");

        assertThat(ex).isInstanceOf(NoSuchElementException.class).isInstanceOf(LocalizedError.class);
    }

    @Test
    void should_formatNamedNotFoundMessage_withResourceAndId() {
        var ex = new ElementNotFoundException("Chain", "c1");

        assertThat(ex.getMessage()).isEqualTo("Chain not found: c1");
        assertThat(ex.errorMessage()).isEqualTo(ErrorMessage.NAMED_NOT_FOUND);
        assertThat(ex.params()).containsExactlyInAnyOrderEntriesOf(Map.of("resource", "Chain", "id", "c1"));
    }

    @Test
    void should_writeNullIdAsText_insteadOfFailing() {
        var ex = new ElementNotFoundException("Chain", null);

        assertThat(ex.params()).containsEntry("id", "null");
        assertThat(ex.getMessage()).isEqualTo("Chain not found: null");
    }
}
