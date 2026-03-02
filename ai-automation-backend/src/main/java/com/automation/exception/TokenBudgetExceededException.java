package com.automation.exception;

public class TokenBudgetExceededException extends WorkflowExecutionException {

    private final long budgetTokens;
    private final long usedTokens;

    public TokenBudgetExceededException(long budgetTokens, long usedTokens) {
        super("Token budget exceeded: used " + usedTokens + " of " + budgetTokens + " allowed tokens");
        this.budgetTokens = budgetTokens;
        this.usedTokens = usedTokens;
    }

    public long getBudgetTokens() {
        return budgetTokens;
    }

    public long getUsedTokens() {
        return usedTokens;
    }
}
