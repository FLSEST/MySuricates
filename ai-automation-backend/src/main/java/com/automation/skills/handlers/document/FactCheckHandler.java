package com.automation.skills.handlers.document;

import com.automation.skills.handlers.CapabilityHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FactCheckHandler implements CapabilityHandler {

    @Override
    public String getName() {
        return "fact_check";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String text = (String) input.get("text");
        String strictness = (String) params.getOrDefault("strictness", "medium");

        return Map.of(
                "command", "Fact-check the following text with " + strictness +
                        " strictness. Flag any unverifiable claims:\n\n" + text,
                "type", "ai-delegated"
        );
    }
}
