package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Schwelle des Keyword-Vorfilters der TODO-Aehnlichkeitssuche. Zur Laufzeit ueber
 * {@code ExecutionSettingsService} aenderbar (Schluessel
 * {@code todo-similarity-keyword-threshold}).
 *
 * <p>Der Wert ist der einzige Hebel gegen zu aggressives Zusammenfuehren: zu niedrig, und
 * Loop-Trigger werden in ein aehnliches offenes TODO verschluckt; zu hoch, und dieselbe Sache
 * entsteht mehrfach. Deshalb gehoert er in die Einstellungen und nicht in den Code (TODO 380818b1).
 */
@Configuration
@ConfigurationProperties(prefix = "automation.todo.similarity")
@Getter
@Setter
public class TodoSimilarityConfig {
    /** Jaccard-Mindestwert, ab dem ein offenes TODO als Kandidat in die KI-Pruefung geht. */
    private double keywordThreshold = 0.2;
}
