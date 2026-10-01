package com.automation.entity;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
     * Fields of the block's JSON besides the ones above — {@code "on_failure": "continue"} next to
     * {@code config} instead of in it. No run reads them; Jackson used to drop them without a word, so the
     * setting silently had no effect (d3aa0b28). They are kept and written back, so the analyzer can report
     * them ({@code unknown-block-field}) wherever the definition came from — save, pool install, import.
     * Sorted, so the definition hash does not depend on their order.
     */
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private Map<String, Object> unknownFields = new TreeMap<>();

    @JsonAnySetter
    public void putUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new TreeMap<>();
        }
        unknownFields.put(key, value);
    }

    /** The {@linkplain #putUnknownField unknown fields}, read only; empty for a well-formed block. */
    @JsonAnyGetter
    public Map<String, Object> unknownFields() {
        return unknownFields == null ? Map.of() : Collections.unmodifiableMap(unknownFields);
    }

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
