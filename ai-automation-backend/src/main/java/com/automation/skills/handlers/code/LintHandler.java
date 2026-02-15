package com.automation.skills.handlers.code;

import com.automation.skills.handlers.CapabilityHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LintHandler implements CapabilityHandler {

    @Override
    public String getName() {
        return "lint";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String code = (String) input.get("code");
        String language = (String) input.getOrDefault("language", "auto-detect");

        return Map.of(
                "command", "Lint the following " + language + " code. " +
                        "Report style issues, anti-patterns, and best practice violations:\n\n" + code,
                "type", "ai-delegated"
        );
    }
}
