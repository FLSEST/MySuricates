package com.automation.skills.handlers.document;

import com.automation.skills.handlers.CapabilityHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OCRHandler implements CapabilityHandler {

    @Override
    public String getName() {
        return "ocr";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        // OCR is delegated to the AI CLI inside the container
        // This handler structures the request for the CLI
        String imagePath = (String) input.get("image");
        return Map.of(
                "command", "Extract all text from the image at: " + imagePath,
                "type", "ai-delegated"
        );
    }
}
