package com.automation.dto.projection;

import com.automation.entity.TriggerSource;

/**
 * Where a run came from, without its steps, metadata, error or input context (TODO a3b89355) — for
 * the audit report page. Of the input context only the parent's trigger source is read, in the
 * database ({@link TriggerSource#PARENT_CONTEXT_KEY}, what {@link TriggerSource#effective} needs): a
 * chain entry's context carries the whole {@code previous_chain_output}.
 *
 * @param parentTriggerSource the context's parent trigger source as stored; {@code null} when absent
 *                            or the context is no valid JSON
 */
public record RunOriginRow(
        String id,
        TriggerSource triggerSource,
        String triggeredByScheduleId,
        String chainRunId,
        String definitionVersionId,
        String parentTriggerSource
) {

    /**
     * A row of {@code WorkflowRunRepository.findOriginColumns}, in its column order. An unknown trigger
     * source name reads as {@code null}; the empty string the query returns for "no parent" as well.
     */
    public static RunOriginRow fromColumns(Object[] row) {
        return new RunOriginRow((String) row[0], TriggerSource.parse(row[1]), (String) row[2], (String) row[3],
                (String) row[4], row[5] instanceof String parent && !parent.isBlank() ? parent : null);
    }
}
