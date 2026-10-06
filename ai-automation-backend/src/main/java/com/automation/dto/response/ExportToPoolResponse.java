package com.automation.dto.response;

import java.util.List;

/**
 * @param sanitizedPaths places the export removed or replaced as instance bindings
 *                       (e.g. {@code blocks[0].config.workflowId} of a sub-workflow block).
 *                       Empty if nothing needed cleaning. Visible instead of only in the log, because a
 *                       removed reference can make the entry incomplete - see
 *                       {@code docs/workflow-pool-kuratierung.md}.
 */
public record ExportToPoolResponse(
        String poolId,
        String filename,
        List<String> sanitizedPaths
) {}
