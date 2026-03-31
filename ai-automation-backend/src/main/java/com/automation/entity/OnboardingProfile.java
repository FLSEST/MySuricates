package com.automation.entity;

import com.automation.util.JsonStringMapConverter;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "onboarding_profiles")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingProfile {

    @Id
    private String id;

    @PrePersist
    private void ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(nullable = false, unique = true)
    private String key;

    @Convert(converter = JsonStringMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    @Builder.Default
    private Map<String, String> name = new HashMap<>();

    @Convert(converter = JsonStringMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, String> description = new HashMap<>();

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OnboardingProfileType type;

    @Builder.Default
    private boolean builtIn = false;

    @Builder.Default
    private int sortOrder = 0;

    @Builder.Default
    private int version = 1;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
