package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Records a workflow run that failed terminally so it can be retried or discarded
 * later. Created automatically by the WorkflowExecutor when a run reaches FAILED
 * status; managed manually via the /api/v1/dlq endpoints.
 */
@Entity
@Table(name = "dead_letter_entries")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadLetterEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "workflow_run_id")
    private String workflowRunId;

    @Column(name = "workflow_id")
    private String workflowId;

    @Column(name = "workflow_name")
    private String workflowName;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "failed_step_index")
    private Integer failedStepIndex;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "last_failure_at", nullable = false)
    private Instant lastFailureAt;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.OPEN;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    public enum Status {
        OPEN,
        RETRIED,
        DISCARDED
    }
}
