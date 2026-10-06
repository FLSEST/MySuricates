package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Creating a workflow from the conversion proposal of a run.
 *
 * <p>{@code blocks} and {@code description} are the proposal from the preview that the user
 * has seen (TODO 935af6d3): if supplied, exactly these are saved, without regenerating the proposal;
 * {@code name} is then mandatory. Without {@code blocks} the server regenerates the proposal
 * (old clients).
 */
public record ConvertRunToWorkflowRequest(
        @NotBlank String projectId,
        String name,
        String description,
        List<BlockDefinition> blocks
) {
    /** Only project and name — the proposal is regenerated server-side. */
    public ConvertRunToWorkflowRequest(String projectId, String name) {
        this(projectId, name, null, null);
    }
}
