package com.automation.event;

/**
 * Fired after an assistant chat response is saved to an AssistantSession.
 * Listeners use this to trigger throttled suggestion refresh for the given page.
 */
public record AssistantSessionUpdatedEvent(String page) {}
