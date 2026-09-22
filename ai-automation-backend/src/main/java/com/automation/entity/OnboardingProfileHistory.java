package com.automation.entity;

import com.automation.util.JsonStringMapConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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

    /** The content before the change, per language (V81). */
    @Convert(converter = JsonStringMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    @Builder.Default
    private Map<String, String> content = new HashMap<>();

    @Column(nullable = false)
    private String changedBy;

    @Column(nullable = false)
    private Instant changedAt;
}
