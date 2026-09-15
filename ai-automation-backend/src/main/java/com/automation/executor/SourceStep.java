package com.automation.executor;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stored facts of a finished top-level step that a compression block may use as its source.
 * {@code root} is set when the step is itself a compression: it then names the original step
 * whose status and exit code every later header shows.
 */
public record SourceStep(String blockName, String stepId, int stepIndex, String blockType,
                         StepStatus status, Integer exitCode, Long durationMs, SourceStep root) {

    /** The step a compression header describes: the original source for derived outputs. */
    public SourceStep origin() {
        return root != null ? root : this;
    }

    public static SourceStep of(String blockName, StepResult step) {
        Map<String, Object> md = step.getMetadata() != null ? step.getMetadata() : Map.of();
        SourceStep root = null;
        if (Boolean.TRUE.equals(md.get("derivedOutput"))
                && md.get("compression") instanceof Map<?, ?> compression
                && compression.get("source") instanceof Map<?, ?> source) {
            root = fromMetadata(source).origin();
        }
        Integer exitCode = md.get("exitCode") instanceof Number n ? n.intValue() : null;
        String blockType = md.get("blockType") instanceof String t ? t : null;
        return new SourceStep(blockName, step.getId(), step.getStepIndex(), blockType,
                step.getStatus(), exitCode, step.getDurationMs(), root);
    }

    /** Flat map for {@code metadata.compression.source}; null fields are omitted. */
    public Map<String, Object> toMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (blockName != null) m.put("block", blockName);
        if (stepId != null) m.put("stepId", stepId);
        m.put("stepIndex", stepIndex);
        if (blockType != null) m.put("blockType", blockType);
        if (status != null) m.put("status", status.name());
        if (exitCode != null) m.put("exitCode", exitCode);
        if (durationMs != null) m.put("durationMs", durationMs);
        return m;
    }

    public static SourceStep fromMetadata(Map<?, ?> m) {
        StepStatus status = m.get("status") instanceof String s ? StepStatus.valueOf(s) : null;
        return new SourceStep(
                m.get("block") instanceof String b ? b : null,
                m.get("stepId") instanceof String id ? id : null,
                m.get("stepIndex") instanceof Number i ? i.intValue() : 0,
                m.get("blockType") instanceof String t ? t : null,
                status,
                m.get("exitCode") instanceof Number e ? e.intValue() : null,
                m.get("durationMs") instanceof Number d ? d.longValue() : null,
                null);
    }
}
