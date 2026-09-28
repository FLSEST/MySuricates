package com.automation.entity;

import com.automation.util.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "completion_triggers")
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompletionTrigger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "source_workflow_id", nullable = false)
    private Workflow sourceWorkflow;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "target_workflow_id", nullable = false)
    private Workflow targetWorkflow;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    @Column(name = "trigger_on_success")
    private boolean triggerOnSuccess = true;

    @Builder.Default
    @Column(name = "trigger_on_failure")
    private boolean triggerOnFailure = false;

    @Builder.Default
    @Column(name = "trigger_always")
    private boolean triggerAlways = false;

    private String description;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> contextVariables = new HashMap<>();

    private Instant lastTriggeredAt;

    @Builder.Default
    private long triggerCount = 0;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
