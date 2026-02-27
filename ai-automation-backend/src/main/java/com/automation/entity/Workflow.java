package com.automation.entity;

import com.automation.util.BlockDefinitionListConverter;
import com.automation.util.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;
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
@EntityListeners(AuditingEntityListener.class)
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

    private String templateId;

    @Builder.Default
    private String testStatus = "UNTESTED";

    private Instant lastTestedAt;

    @OneToMany(mappedBy = "workflow", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WorkflowRun> runs = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
