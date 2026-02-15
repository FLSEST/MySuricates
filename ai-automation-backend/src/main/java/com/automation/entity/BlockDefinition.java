package com.automation.entity;

import lombok.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockDefinition {

    private String type;

    @Builder.Default
    private Map<String, Object> config = new HashMap<>();

    @Builder.Default
    private List<String> requiredCapabilities = new ArrayList<>();

    public boolean shouldStopOnFailure() {
        Object onFailure = config.get("on_failure");
        return onFailure == null || "stop".equals(onFailure);
    }
}
