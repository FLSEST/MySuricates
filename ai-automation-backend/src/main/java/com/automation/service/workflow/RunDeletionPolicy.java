package com.automation.service.workflow;

import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether runs may be deleted through the API ({@code DELETE /api/v1/executions/{id}}).
 *
 * <p>Startup-only on purpose: {@code automation.executions.allow-delete} is read from the
 * configuration and there is no REST or settings path to flip it. An instance that keeps its run
 * history as evidence must not have it turned back on by whoever holds a leaked API key.
 *
 * <p>Does not cover the operational jobs in {@link RunCleanupService} (interrupted-run sweep and
 * the configurable retention) — they are the operator's own housekeeping; disable retention via
 * {@code run-retention-days = 0} if nothing may ever disappear.
 */
@Component
public class RunDeletionPolicy {

    private final boolean allowDelete;

    public RunDeletionPolicy(@Value("${automation.executions.allow-delete:true}") boolean allowDelete) {
        this.allowDelete = allowDelete;
    }

    public boolean isAllowed() {
        return allowDelete;
    }

    /** @throws HardeningException (403 RUN_DELETION_DISABLED) when deletion is switched off */
    public void requireAllowed() {
        if (!allowDelete) {
            throw new HardeningException(ErrorCode.RUN_DELETION_DISABLED,
                    "Deleting runs is disabled on this instance (automation.executions.allow-delete=false)");
        }
    }
}
