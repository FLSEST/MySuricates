package com.automation.executor.compress;

import java.util.Map;

/** One deterministic compression operation. Implementations are stateless and thread-safe. */
public interface CompressionStage {

    String op();

    StageKind inputKind();

    StageKind outputKind();

    /** Checks the stage config before anything runs. @throws CompressionException with a German message */
    void validate(Map<String, Object> config);

    StageResult apply(String input, Map<String, Object> config, RegexGuard guard);
}
