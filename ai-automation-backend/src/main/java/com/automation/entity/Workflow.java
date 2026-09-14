package com.automation.entity;

import com.automation.util.BlockDefinitionListConverter;
import com.automation.util.JsonMapConverter;
import com.automation.util.StringListConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "workflows")
@SQLRestriction("deleted_at IS NULL")
@EntityListeners(AuditingEntityListener.class)
@NamedEntityGraph(
        name = "Workflow.forExecution",
        attributeNodes = @NamedAttributeNode("activatedSkills")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workflow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    private String icon;

    @Convert(converter = BlockDefinitionListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<BlockDefinition> blocks = new ArrayList<>();

    @Builder.Default
    private String version = "1.0.0";

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "default_context", columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> defaultContext = new HashMap<>();

    @ManyToMany
    @JoinTable(
            name = "workflow_skills",
            joinColumns = @JoinColumn(name = "workflow_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id")
    )
    @Builder.Default
    private List<Skill> activatedSkills = new ArrayList<>();

    @Column(columnDefinition = "text")
    private String instructions;

    private String model;

    private String provider;

    /** Reasoning-effort level (low/medium/high/xhigh); null = provider default. */
    private String effort;

    private String templateId;

    @Column(name = "block_types")
    @Builder.Default
    private String blockTypes = "";

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Column(length = 32)
    private String category;

    @Column(name = "tags_generated_at")
    private Instant tagsGeneratedAt;

    @Column(name = "tags_user_edited", nullable = false)
    @Builder.Default
    private boolean tagsUserEdited = false;

    @Builder.Default
    private String testStatus = "UNTESTED";

    private Instant lastTestedAt;

    @OneToMany(mappedBy = "workflow", fetch = FetchType.LAZY)
    @Builder.Default
    private List<WorkflowRun> runs = new ArrayList<>();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "stable_synced_at")
    private Instant stableSyncedAt;

    /** Sealed definitions reject changes and refuse to run if their hash no longer matches {@link #sealedHash}. */
    @Column(nullable = false)
    @Builder.Default
    private boolean sealed = false;

    @Column(name = "sealed_hash")
    private String sealedHash;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void computeBlockTypes() {
        if (blocks != null && !blocks.isEmpty()) {
            blockTypes = blocks.stream()
                    .map(BlockDefinition::getType)
                    .filter(t -> t != null && !t.isEmpty())
                    .distinct()
                    .sorted()
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
        } else {
            blockTypes = "";
        }
    }
}
