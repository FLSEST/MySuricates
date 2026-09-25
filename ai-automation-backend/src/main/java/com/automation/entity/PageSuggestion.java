package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "page_suggestions", uniqueConstraints = @UniqueConstraint(columnNames = {"page", "locale"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageSuggestion {

    @Id
    private String id;

    @Column(nullable = false)
    private String page;

    /** Language of the suggestions, {@code de} or {@code en} (V91). */
    @Column(nullable = false)
    @Builder.Default
    private String locale = "de";

    @Column(columnDefinition = "text", nullable = false)
    @Builder.Default
    private String suggestions = "[]";

    @Column(name = "generated_by", nullable = false)
    @Builder.Default
    private String generatedBy = "CLAUDE";

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "based_on_sessions", nullable = false)
    @Builder.Default
    private int basedOnSessions = 0;

    @PrePersist
    void prePersist() {
        if (id == null) id = java.util.UUID.randomUUID().toString();
        if (generatedAt == null) generatedAt = Instant.now();
    }
}
