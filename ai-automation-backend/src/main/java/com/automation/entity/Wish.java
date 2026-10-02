package com.automation.entity;

import com.automation.util.JsonListConverter;
import com.automation.util.JsonMapConverter;
import com.automation.util.StringListConverter;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "wishes")
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wish {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "original_text", nullable = false, columnDefinition = "text")
    private String originalText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private WishStatus status = WishStatus.RECEIVED;

    /**
     * The phase a wish was in when it ended {@code FAILED} or {@code CANCELLED} (V97, TODO 92bffe64) — set by
     * {@link #setStatus}, null for every other status. The timeline used to guess it from the iteration
     * history and showed "evaluating" for a refinement that failed before its entry existed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "stopped_phase")
    private WishStatus stoppedPhase;

    @Column(name = "project_id")
    private String projectId;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> decomposition = new HashMap<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "generated_workflow_ids", columnDefinition = "text")
    @Builder.Default
    private List<String> generatedWorkflowIds = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "reference_workflow_ids", columnDefinition = "text")
    @Builder.Default
    private List<String> referenceWorkflowIds = new ArrayList<>();

    @Column(name = "generated_chain_id")
    private String generatedChainId;

    @Column(name = "iteration_count")
    @Builder.Default
    private int iterationCount = 0;

    @Column(name = "max_iterations")
    @Builder.Default
    private int maxIterations = 5;

    @Column(name = "auto_refine", nullable = false)
    @Builder.Default
    private boolean autoRefine = false;

    @Column(name = "current_run_id")
    private String currentRunId;

    @Column(name = "result_summary", columnDefinition = "text")
    private String resultSummary;

    @Column(columnDefinition = "text")
    private String error;

    @Convert(converter = JsonListConverter.class)
    @Column(name = "iteration_history", columnDefinition = "text")
    @Builder.Default
    private List<Map<String, Object>> iterationHistory = new ArrayList<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Sets the status and keeps {@link #stoppedPhase}: entering {@code FAILED}/{@code CANCELLED} from a running or
     * waiting status records that status; staying terminal keeps it; any other status (a retry) clears it.
     * Every status change goes through here, so no caller has to remember it.
     */
    public void setStatus(WishStatus status) {
        boolean stops = status == WishStatus.FAILED || status == WishStatus.CANCELLED;
        if (!stops) {
            stoppedPhase = null;
        } else if (this.status != null && !isTerminal(this.status)) {
            stoppedPhase = this.status;
        }
        this.status = status;
    }

    private static boolean isTerminal(WishStatus status) {
        return status == WishStatus.COMPLETED || status == WishStatus.FAILED || status == WishStatus.CANCELLED;
    }
}
