package com.automation.service.chain;

import com.automation.entity.ChainEntry;
import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowChain;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One chain run in progress, as {@link ChainExecutor} walks its entries: what it runs (fixed) and what
 * the entries so far left behind (mutable). Only the chain's own virtual thread touches it.
 */
@RequiredArgsConstructor
final class ChainRunProgress {

    final String runId;
    final ChainRun run;
    final WorkflowChain chain;
    private final Map<String, Object> inputContext;
    private final TriggerSource startedBy;
    final AtomicBoolean cancelled;

    /** Output of the last completed entry; the next entry's {@code {{ previous_chain_output }}}. */
    String previousOutput;
    boolean hasFailures;
    boolean hasWarnings;

    boolean isCancelled() {
        return cancelled != null && cancelled.get();
    }

    /** The entry run's context: chain input, then the entry's own input, then the keys only the chain sets. */
    Map<String, Object> contextFor(ChainEntry entry) {
        Map<String, Object> context = new HashMap<>();
        if (inputContext != null) {
            context.putAll(inputContext);
        }
        if (entry.getInputContext() != null) {
            context.putAll(entry.getInputContext());
        }
        // Always set it (empty string for the first entry) so {{ previous_chain_output }}
        // resolves instead of leaking the literal placeholder to the LLM.
        context.put("previous_chain_output", previousOutput != null ? previousOutput : "");
        context.put(TriggerSource.PARENT_CONTEXT_KEY, startedBy.name()); // never taken from the input
        // {{ chain_run_id }}, and the entry run records it on its row when created (WorkflowRun.chainRunId)
        context.put(TriggerSource.CHAIN_RUN_CONTEXT_KEY, runId);
        return context;
    }

    /**
     * With stop-on-failure — set on the chain and on the entry — fails the chain with {@code error}
     * and returns true.
     */
    boolean stopOnFailure(ChainEntry entry, String error) {
        if (!(entry.isStopOnFailure() && chain.isStopOnFailure())) {
            return false;
        }
        run.setStatus(RunStatus.FAILED);
        run.setError(error);
        return true;
    }

    /**
     * The status of a chain that went through all its entries: failed when entries failed and none
     * completed, completed with errors on any failure or warning, else completed.
     */
    RunStatus finalStatus() {
        boolean anyCompleted = run.getEntryRuns().stream()
                .anyMatch(e -> e.getStatus() == RunStatus.COMPLETED
                            || e.getStatus() == RunStatus.COMPLETED_WITH_ERRORS);
        if (hasFailures && !anyCompleted) {
            return RunStatus.FAILED;
        }
        if (hasFailures || hasWarnings) {
            return RunStatus.COMPLETED_WITH_ERRORS;
        }
        return RunStatus.COMPLETED;
    }
}
