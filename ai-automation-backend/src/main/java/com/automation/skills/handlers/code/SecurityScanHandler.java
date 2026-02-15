package com.automation.skills.handlers.code;

import com.automation.skills.handlers.CapabilityHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SecurityScanHandler implements CapabilityHandler {

    @Override
    public String getName() {
        return "security_scan";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String code = (String) input.get("code");

        return Map.of(
                "command", "Scan the following code for security vulnerabilities. " +
                        "Check for OWASP Top 10 issues, injection flaws, XSS, " +
                        "authentication problems, and sensitive data exposure:\n\n" + code,
                "type", "ai-delegated"
        );
    }
}
