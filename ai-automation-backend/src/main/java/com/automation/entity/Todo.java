package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "todos")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Todo {

    public enum Status { OPEN, IN_PROGRESS, DONE, BACKLOG }
    public enum Priority { LOW, MEDIUM, HIGH }
    public enum Source { USER, AI, WORKFLOW }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Priority priority = Priority.MEDIUM;

    @Column(columnDefinition = "text")
    private String tags;

    @Column(name = "project_id")
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Source source = Source.USER;

    @Column(name = "source_agent")
    private String sourceAgent;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "workflow_run_id")
    private String workflowRunId;

    @Column(name = "reference_type")
    private String referenceType;

    @Column(name = "reference_id")
    private String referenceId;

    @Column(name = "related_todo_id")
    private String relatedTodoId;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
