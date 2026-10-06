package com.automation.mapper;

import com.automation.dto.response.WorkflowRunListItemResponse;
import com.automation.entity.StepStatus;

/**
 * Step count and last step of one run — what a run list shows instead of the steps (TODO
 * a05c9504, 7fd9f654). {@code lastOutputHead} is the start of the last output, one character
 * longer than the preview, so {@link #preview()} can still tell whether it was cut.
 */
public record RunStepSummary(int count, StepStatus lastStatus, String lastOutputHead) {

    /** A run without steps. */
    public static final RunStepSummary NONE = new RunStepSummary(0, null, null);

    /** Characters of the last output a summary needs: the preview plus one to detect the cut. */
    public static final int HEAD_LENGTH = WorkflowRunListItemResponse.PREVIEW_LENGTH + 1;

    /** Shortened preview; {@code null} stays {@code null} so that "no value" does not look like "empty". */
    public String preview() {
        if (lastOutputHead == null) {
            return null;
        }
        return lastOutputHead.length() <= WorkflowRunListItemResponse.PREVIEW_LENGTH
                ? lastOutputHead
                : lastOutputHead.substring(0, WorkflowRunListItemResponse.PREVIEW_LENGTH) + " […]";
    }
}
