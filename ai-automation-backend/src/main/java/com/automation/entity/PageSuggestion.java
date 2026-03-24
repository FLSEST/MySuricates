package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "page_suggestions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageSuggestion {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String page;

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
