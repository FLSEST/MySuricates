package com.automation.entity;

import com.automation.util.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "step_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_run_id", nullable = false)
    private WorkflowRun workflowRun;

    @Column(nullable = false)
    private int stepIndex;

    @Column(columnDefinition = "text")
    private String input;

    @Column(columnDefinition = "text")
    private String output;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private StepStatus status = StepStatus.PENDING;

    private Instant startedAt;

    private Instant completedAt;

    private Long durationMs;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Cumulative snapshot of user-defined context variables at the end of this step.
     * Excludes derivable step_N_output variables and system-injected vars.
     * Used to reconstruct the execution context when resuming an INTERRUPTED run.
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "context_delta", columnDefinition = "text")
    private Map<String, Object> contextDelta;
}
