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

    @OneToOne(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private Container container;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Workflow> workflows = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
