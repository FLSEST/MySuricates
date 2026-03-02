package com.automation.exception;

public class CapabilityNotFoundException extends ResourceNotFoundException {

    public CapabilityNotFoundException(String skillId, String capabilityName) {
        super("Capability", capabilityName + " in skill " + skillId);
    }
}
