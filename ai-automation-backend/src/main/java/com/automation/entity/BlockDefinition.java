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

    /**
     * Optional human-readable name used to reference this block's output from later
     * blocks via {@code {{ block.<name>.output }}}. Allowed characters: letters,
     * digits, underscore, hyphen — no dots or whitespace.
     */
    private String name;

    @Builder.Default
    private Map<String, Object> config = new HashMap<>();

    @Builder.Default
    private List<String> requiredCapabilities = new ArrayList<>();

    @Builder.Default
    private List<String> skillIds = new ArrayList<>();

    /**
     * Whether a failure of this block ends the run (or the switch case it sits in). Fail-closed:
     * only an explicit {@code on_failure: "continue"} (trimmed, any case) lets execution go on —
     * a missing value, {@code "stop"} and every unknown value ({@code "fail"}, a typo, a non-string)
     * stop, so a mistyped setting can never silently turn a failure into a pass (2b361abe).
     * The one place that reads {@code on_failure}; executors call this, never the raw config.
     */
    public boolean shouldStopOnFailure() {
        Object onFailure = config == null ? null : config.get("on_failure");
        return !(onFailure instanceof String value && "continue".equalsIgnoreCase(value.trim()));
    }
}
