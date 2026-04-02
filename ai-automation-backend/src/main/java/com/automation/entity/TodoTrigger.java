package com.automation.entity;

import com.automation.util.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "todo_triggers")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TodoTrigger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "tag_pattern", nullable = false)
    private String tagPattern;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "source_filter")
    private String sourceFilter;

    @Column(name = "min_priority")
    private String minPriority;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "target_workflow_id", nullable = false)
    private Workflow targetWorkflow;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "context_template", columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> contextTemplate = new HashMap<>();

    @Builder.Default
    private boolean enabled = true;

    @Column(name = "max_attempts")
    @Builder.Default
    private int maxAttempts = 3;

    @Column(name = "auto_close")
    @Builder.Default
    private boolean autoClose = true;

    @Column(name = "process_backlog")
    @Builder.Default
    private boolean processBacklog = true;

    private String description;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @Column(name = "trigger_count")
    @Builder.Default
    private long triggerCount = 0;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
