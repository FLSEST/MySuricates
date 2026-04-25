package com.automation.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetExceededExceptionTest {

    @Test
    void should_storeBudgetAndUsedTokens_when_constructedWithBothValues() {
        var ex = new TokenBudgetExceededException(5000L, 7300L);

        assertThat(ex.getBudgetTokens()).isEqualTo(5000L);
        assertThat(ex.getUsedTokens()).isEqualTo(7300L);
    }

    @Test
    void should_containBothNumbersInMessage_when_constructed() {
        var ex = new TokenBudgetExceededException(5000L, 7300L);

        assertThat(ex.getMessage()).contains("5000", "7300");
    }

    @Test
    void should_extendWorkflowExecutionException_when_constructed() {
        var ex = new TokenBudgetExceededException(1000L, 2000L);

        assertThat(ex).isInstanceOf(WorkflowExecutionException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_handleZeroValues_when_constructedWithZeros() {
        var ex = new TokenBudgetExceededException(0L, 0L);

        assertThat(ex.getBudgetTokens()).isZero();
        assertThat(ex.getUsedTokens()).isZero();
        assertThat(ex.getMessage()).isNotNull();
    }

    @Test
    void should_handleLargeValues_when_constructedWithMaxLong() {
        var ex = new TokenBudgetExceededException(Long.MAX_VALUE, Long.MAX_VALUE);

        assertThat(ex.getBudgetTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(ex.getUsedTokens()).isEqualTo(Long.MAX_VALUE);
    }
}
