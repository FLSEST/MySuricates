package com.automation.dto.projection;

import com.automation.entity.TriggerSource;

import java.util.Map;

/**
 * Where a run came from, without its steps, metadata or error (TODO a3b89355) — for the audit
 * report page. {@code inputContext} carries the parent's trigger source of a chain entry
 * ({@link TriggerSource#PARENT_CONTEXT_KEY}), which is what {@link TriggerSource#effective} reads.
 */
public record RunOriginRow(
        String id,
        TriggerSource triggerSource,
        String triggeredByScheduleId,
        String chainRunId,
        String definitionVersionId,
        Map<String, Object> inputContext
) {}
