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
@Table(name = "scheduled_workflows")
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledWorkflow {

    public enum ScheduleType {
        CRON, ONCE, DELAYED
    }

    public enum TargetType {
        WORKFLOW, CHAIN
    }

    public enum ConflictBehavior {
        SKIP, OVERLAP
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

    /** Human-readable delay expression for DELAYED schedules, e.g. "2h", "30m", "1d". */
    @Column(name = "delay_expression")
    private String delayExpression;

    @Builder.Default
    private boolean enabled = true;

    private String description;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> inputContext = new HashMap<>();

    private Instant lastRunAt;

    private Instant nextRunAt;

    /** Optional end date for CRON schedules — disables the schedule when now >= endDate. */
    @Column(name = "end_date")
    private Instant endDate;

    /** Maximum number of runs for CRON schedules (0 = unlimited). */
    @Column(name = "max_runs")
    @Builder.Default
    private int maxRuns = 0;

    /** Counter incremented after each execution. */
    @Column(name = "run_count")
    @Builder.Default
    private int runCount = 0;

    /** Whether to skip or overlap when the target already has a RUNNING execution. */
    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_behavior")
    @Builder.Default
    private ConflictBehavior conflictBehavior = ConflictBehavior.SKIP;

    /**
     * Catch this schedule up (run once) when an occurrence was missed during a suspend or while the
     * backend was stopped — even if the global setting {@code scheduler-catch-up-on-resume} is off.
     * Default false: then the global setting decides (on by default; off = skip forward to the next
     * future occurrence). Caught-up runs go through the sequential CatchUpQueue, never all at once.
     */
    @Column(name = "catch_up_on_resume", nullable = false)
    @Builder.Default
    private boolean catchUpOnResume = false;

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
