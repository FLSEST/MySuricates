package com.automation.skills.handlers;

import java.util.Map;

public interface CapabilityHandler {

    String getName();

    Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params);
}
