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
}
