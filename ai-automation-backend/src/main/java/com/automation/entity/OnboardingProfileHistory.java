package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "onboarding_profile_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingProfileHistory {

    @Id
    private String id;

    @PrePersist
    private void ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(nullable = false)
    private String profileId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private String changedBy;

    @Column(nullable = false)
    private Instant changedAt;
}
