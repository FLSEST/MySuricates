package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "audit_reports")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditReport {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "project_id", nullable = false)
    private String projectId;
    @Column(name = "workflow_id", nullable = false)
    private String workflowId;
    @Column(name = "workflow_run_id", nullable = false, unique = true)
    private String workflowRunId;
    @Column(name = "audit_key", nullable = false)
    private String auditKey;
    @Column(nullable = false)
    private String category;
    @Column(nullable = false)
    private String title;
    @Column(name = "markdown_content", columnDefinition = "text")
    private String markdownContent;
    @Enumerated(EnumType.STRING)
    @Column(name = "gate_status")
    private GateStatus gateStatus;
    @Column(name = "gate_description", columnDefinition = "text")
    private String gateDescription;
    private String provider;
    private String model;
    @Column(name = "workflow_version")
    private String workflowVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_status", nullable = false)
    private AuditIngestionStatus ingestionStatus;
    @Column(name = "ingestion_error", columnDefinition = "text")
    private String ingestionError;
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
