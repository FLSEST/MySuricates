package com.automation.exception;

public class SkillNotFoundException extends ResourceNotFoundException {

    public SkillNotFoundException(String id) {
        super("Skill", id);
    }
}
