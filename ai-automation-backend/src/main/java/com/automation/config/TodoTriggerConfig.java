package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Pause window for the TodoTrigger mechanism (self-improvement loop).
 *
 * <p>Deliberately a timestamp and not an on/off flag, mirroring
 * {@code automation.scheduler.skip-until}: a boolean stays set until somebody remembers to clear
 * it, and TodoTriggers failing to fire is silent — no error, no red status, the loop simply stops
 * picking up todos. A window expires on its own.
 *
 * <p>Same reasoning applies to using this instead of flipping each trigger's {@code enabled}
 * column via the API: that column is persisted state without an expiry date.
 *
 * <p>Note that {@code automation.todo-trigger.poll-interval-ms} lives under the same prefix but is
 * read directly by the {@code @Scheduled} annotation on
 * {@link com.automation.service.trigger.TodoTriggerService#processBacklog()}, not through here.
 */
@Configuration
@ConfigurationProperties(prefix = "automation.todo-trigger")
@Getter
@Setter
public class TodoTriggerConfig {

    /**
     * ISO-8601 instant (e.g. {@code 2026-08-17T22:00:00Z}); while now is before it, no TodoTrigger
     * fires — neither the backlog poll nor the todo-created event path. Empty (default) = no pause.
     */
    private String skipUntil = "";
}
