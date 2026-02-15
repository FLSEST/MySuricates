package com.automation.skills.handlers.document;

import com.automation.skills.handlers.CapabilityHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class GrammarCheckHandler implements CapabilityHandler {

    @Override
    public String getName() {
        return "grammar_check";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String text = (String) input.get("text");

        return Map.of(
                "command", "Check the following text for grammar, spelling, and style issues. " +
                        "Provide corrections and explanations:\n\n" + text,
                "type", "ai-delegated"
        );
    }
}
