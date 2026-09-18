package com.automation.dto.response;

import java.util.List;

/**
 * @param sanitizedPaths Stellen, die der Export als Instanz-Bindung entfernt oder ersetzt hat
 *                       (z. B. {@code blocks[0].config.workflowId} eines sub-workflow-Blocks).
 *                       Leer, wenn nichts zu bereinigen war. Sichtbar statt nur im Log, weil eine
 *                       entfernte Referenz den Eintrag unvollständig machen kann — siehe
 *                       {@code docs/workflow-pool-kuratierung.md}.
 */
public record ExportToPoolResponse(
        String poolId,
        String filename,
        List<String> sanitizedPaths
) {}
