package com.automation.service.assistant.context;

/**
 * A section that contributes context to the assistant's system prompt.
 * Each implementation fetches data from one domain (runs, todos, wishes, schedules).
 */
public interface AssistantContextSection {

    /**
     * Appends this section's context to the prompt builder.
     *
     * @param sb      the prompt being built
     * @param compact true if this section should be rendered in compact mode
     */
    void append(StringBuilder sb, boolean compact);
}
