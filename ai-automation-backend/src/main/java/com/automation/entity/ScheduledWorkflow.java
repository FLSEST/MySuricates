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
@Table(name = "scheduled_workflows")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledWorkflow {

    public enum ScheduleType {
        CRON, ONCE
    }

    public enum TargetType {
        WORKFLOW, CHAIN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    @Builder.Default
    private TargetType targetType = TargetType.WORKFLOW;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "workflow_id")
    private Workflow workflow;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "chain_id")
    private WorkflowChain chain;

    @Column(nullable = false)
    private String cronExpression;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false)
    @Builder.Default
    private ScheduleType scheduleType = ScheduleType.CRON;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Builder.Default
    private boolean enabled = true;

    private String description;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> inputContext = new HashMap<>();

    private Instant lastRunAt;

    private Instant nextRunAt;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public String getTargetName() {
        if (targetType == TargetType.CHAIN && chain != null) {
            return chain.getName();
        }
        return workflow != null ? workflow.getName() : "Unknown";
    }
}
