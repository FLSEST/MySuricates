package com.automation.event;

/**
 * Fired after an assistant chat response is saved to an AssistantSession.
 * Listeners use this to trigger throttled suggestion refresh for the given page, in the language
 * of the chat ({@code de}/{@code en}; suggestions are kept per page and language, 5157fac2).
 */
public record AssistantSessionUpdatedEvent(String page, String locale) {}
