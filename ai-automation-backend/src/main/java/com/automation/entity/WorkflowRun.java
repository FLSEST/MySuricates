package com.automation.entity;

import com.automation.util.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "workflow_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    /**
     * Name snapshot taken at run creation. Stable even when the workflow is
     * soft-deleted (hidden by @SQLRestriction) or when the run is read outside
     * a JPA session (OSIV disabled → lazy workflow load would throw).
     */
    @Column(name = "workflow_name")
    private String workflowName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RunStatus status = RunStatus.PENDING;

    @OneToMany(mappedBy = "workflowRun", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepIndex ASC")
    @BatchSize(size = 30)  // Batch lazy loads of steps across runs (e.g. the executions list maps 50 runs) → IN-queries instead of N+1.
    @Builder.Default
    private List<StepResult> steps = new ArrayList<>();

    private Instant startedAt;

    private Instant completedAt;

    private Long durationMs;

    private Integer userRating;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> error = new HashMap<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> inputContext = new HashMap<>();

    @Column(name = "triggered_by_schedule_id")
    private String triggeredByScheduleId;

    /** SHA-256 of the executed workflow definition — provenance (automation.hardening.provenance). */
    @Column(name = "definition_hash")
    private String definitionHash;

    /** Version row whose snapshot has {@link #definitionHash}, if any. */
    @Column(name = "definition_version_id")
    private String definitionVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source")
    private TriggerSource triggerSource;

    public void addStep(StepResult step) {
        step.setWorkflowRun(this);
        if (step.getStepIndex() <= 0) {
            step.setStepIndex(steps.size());
        }
        steps.add(step);
    }
}
