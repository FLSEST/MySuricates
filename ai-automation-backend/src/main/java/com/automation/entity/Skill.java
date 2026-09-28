package com.automation.entity;

import com.automation.util.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "skills")
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Skill {

    @Id
    private String id;

    @PrePersist
    private void ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SkillCategory category;

    @Column(columnDefinition = "text")
    private String aiContext;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> configSchema = new HashMap<>();

    @Builder.Default
    private boolean builtin = false;

    @Builder.Default
    private String version = "1.0.0";

    @OneToMany(mappedBy = "skill", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Capability> capabilities = new ArrayList<>();

    @ManyToMany(mappedBy = "activatedSkills")
    @Builder.Default
    private List<Workflow> workflows = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
