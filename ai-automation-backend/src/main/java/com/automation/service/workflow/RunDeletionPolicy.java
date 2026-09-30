package com.automation.service.workflow;

import com.automation.exception.ErrorCode;
import com.automation.exception.ErrorMessage;
import com.automation.exception.HardeningException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Whether runs may be deleted through the API ({@code DELETE /api/v1/executions/{id}}).
 *
 * <p>Startup-only on purpose: {@code automation.executions.allow-delete} is read from the
 * configuration and there is no REST or settings path to flip it. An instance that keeps its run
 * history as evidence must not have it turned back on by whoever holds a leaked API key.
 *
 * <p>The operational jobs in {@link RunCleanupService} (interrupted-run sweep and the configurable
 * retention) stay the operator's own housekeeping; disable retention via
 * {@code run-retention-days = 0} if nothing may ever disappear. What a leaked key must not do is
 * reach the same deletion by the back door: with deletion switched off, the retention can only be
 * made longer at runtime, never shorter ({@link #requireRetentionChangeAllowed}, TODO 8dbd7e10).
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

    /**
     * A runtime change of {@code run-retention-days} from {@code currentDays} to {@code requestedDays}.
     * Free while deletion is allowed; otherwise only a longer retention passes — {@code 0} (off)
     * counts as the longest, so from 0 nothing but 0 is accepted.
     *
     * @throws HardeningException (403 RUN_DELETION_DISABLED) for a shorter retention while deletion is off
     */
    public void requireRetentionChangeAllowed(int currentDays, int requestedDays) {
        if (allowDelete || keptDays(requestedDays) >= keptDays(currentDays)) {
            return;
        }
        throw new HardeningException(ErrorMessage.RUN_RETENTION_LOCKED,
                Map.of("current", String.valueOf(currentDays), "requested", String.valueOf(requestedDays)));
    }

    private static long keptDays(int retentionDays) {
        return retentionDays <= 0 ? Long.MAX_VALUE : retentionDays;
    }

    /** @throws HardeningException (403 RUN_DELETION_DISABLED) when deletion is switched off */
    public void requireAllowed() {
        if (!allowDelete) {
            throw new HardeningException(ErrorCode.RUN_DELETION_DISABLED,
                    "Deleting runs is disabled on this instance (automation.executions.allow-delete=false)",
                    Map.of());
        }
    }
}
