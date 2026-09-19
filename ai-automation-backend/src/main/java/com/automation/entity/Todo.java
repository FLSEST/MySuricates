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

    public enum Status { OPEN, IN_PROGRESS, DONE, BACKLOG, ARCHIVED }
    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
    public enum Source { USER, AI, WORKFLOW }
    public enum ResolutionState { IDLE, RUNNING, FAILED, SUCCEEDED }

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

    @Column(name = "linked_workflow_id")
    private String linkedWorkflowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Source source = Source.USER;

    @Column(name = "source_agent")
    private String sourceAgent;

    @Column(name = "session_id")
    private String sessionId;

    /**
     * Konversation der Agenten-Sitzung, aus der dieses TODO stammt — die UUID hinter
     * {@code claude --resume}.
     *
     * <p>Nicht dasselbe wie {@link #sessionId}: das traegt die Shell-Session
     * ({@code $SHELL_SESSION_ID}) und reicht nicht, um eine Sitzung wiederherzustellen. Vorher
     * stand der Resume-Befehl nur im Fliesstext der Beschreibung, weil ein Agent ihn dort
     * hinschrieb — prompt-enforced statt backend-enforced, und entsprechend luckenhaft
     * (63 % der Claude-Code-TODOs, Messung 2026-09-16). V76 traegt die vorhandenen Werte
     * einmalig aus den Beschreibungen nach (TODO 3497533c).
     */
    @Column(name = "agent_session_ref")
    private String agentSessionRef;

    @Column(name = "workflow_run_id")
    private String workflowRunId;

    @Column(name = "reference_type")
    private String referenceType;

    @Column(name = "reference_id")
    private String referenceId;

    // Single-TODO export link (to a task tracker), decoupled from reference_type.
    @Column(name = "exported_provider")
    private String exportedProvider;

    @Column(name = "exported_ref")
    private String exportedRef;

    @Column(name = "exported_url")
    private String exportedUrl;

    @Column(name = "related_todo_id")
    private String relatedTodoId;

    @Column(name = "due_date")
    private Instant dueDate;

    @Column(name = "fix_attempts")
    @Builder.Default
    private int fixAttempts = 0;

    @Column(name = "last_fix_run_id")
    private String lastFixRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_state")
    private ResolutionState resolutionState;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
