package com.automation.entity;

import com.automation.util.JsonMapConverter;
import com.automation.util.StringListConverter;
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
@Table(name = "wishes")
@EntityListeners(AuditingEntityListener.class)
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

    @Column(name = "current_run_id")
    private String currentRunId;

    @Column(name = "result_summary", columnDefinition = "text")
    private String resultSummary;

    @Column(columnDefinition = "text")
    private String error;

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
}
