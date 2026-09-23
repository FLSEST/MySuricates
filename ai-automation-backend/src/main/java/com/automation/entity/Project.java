package com.automation.entity;

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
@Table(name = "projects")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    private String subtitle;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectType type;

    @Column(nullable = false)
    private String path;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> config = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.STOPPED;

    @Column(name = "ai_context", columnDefinition = "text")
    private String aiContext;

    // No PERSIST cascade and no orphanRemoval (TODO c2af1a67): the container row is written
    // explicitly by ContainerService (upsert); the PERSIST cascade un-scheduled a pending delete
    // on flush and produced the UNIQUE(containers.project_id) bug cd1685ff. MERGE stays so that a
    // Project referencing a detached Container can be merged without TransientObjectException;
    // REMOVE stays so that deleting a Project in the same persistence context as its (already
    // removed) Container flushes cleanly — the schema cascades the delete anyway (V2).
    //
    // A query that lists projects — or workflows/runs with their project — must fetch this along
    // (LEFT JOIN FETCH, or "project.container" in an entity graph): a nullable mappedBy one-to-one
    // cannot be proxied, and Hibernate re-selects it for every result row whose project has no
    // container (TODO 13d1e2a2; RequestCostRatchetTest catches it on the measured routes).
    @OneToOne(mappedBy = "project", cascade = {CascadeType.MERGE, CascadeType.REMOVE})
    private Container container;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Workflow> workflows = new ArrayList<>();

    @Column(name = "archived_at")
    private Instant archivedAt;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
