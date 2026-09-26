package com.automation.entity;

import lombok.*;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChainEntry {

    private String workflowId;
    private String label;
    private int sortOrder;

    @Builder.Default
    private boolean stopOnFailure = true;

    @Builder.Default
    private Map<String, Object> inputContext = new HashMap<>();

    /** Wie lange die Kette höchstens auf diesen Eintrag wartet (5–1440 min); null = globale Vorgabe (TODO cc0eb3ba). */
    private Integer timeoutMinutes;

    /** Aufrufe von vor {@code timeoutMinutes} (TODO cc0eb3ba). */
    public ChainEntry(String workflowId, String label, int sortOrder, boolean stopOnFailure,
                      Map<String, Object> inputContext) {
        this(workflowId, label, sortOrder, stopOnFailure, inputContext, null);
    }
}
