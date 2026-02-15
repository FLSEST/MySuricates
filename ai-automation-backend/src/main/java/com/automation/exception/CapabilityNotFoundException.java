package com.automation.exception;

public class CapabilityNotFoundException extends RuntimeException {

    public CapabilityNotFoundException(String skillId, String capabilityName) {
        super("Capability '" + capabilityName + "' not found in skill: " + skillId);
    }
}
