package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "todos")
@EntityListeners(AuditTimestampListener.class)
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
    /** Scope and uncertainty of a task - coarse, so that humans and AI use the same scale. */
    public enum Complexity { S, M, L, XL }
    /** Who set the estimate last; a human overrides the AI. */
    public enum RatedBy { USER, AI }

    /**
     * State of a question on the TODO (TODO 68a5e24e): QUESTION as long as a question is open - even if
     * there is an earlier decision -, DECIDED with a decision and without an open question, otherwise NONE.
     */
    public enum DecisionState {
        QUESTION, DECIDED, NONE;

        public static DecisionState of(String openQuestion, String decision) {
            if (openQuestion != null) {
                return QUESTION;
            }
            return decision != null ? DECIDED : NONE;
        }
    }

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

    /**
     * Events that Spring Data publishes on the next save/saveAll ({@link #domainEvents()}).
     * Not persisted; the builder does not set the field.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    private final transient List<Object> domainEvents = new ArrayList<>();

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
     * Conversation of the agent session this TODO comes from - the UUID behind
     * {@code claude --resume}.
     *
     * <p>Not the same as {@link #sessionId}: that carries the shell session
     * ({@code $SHELL_SESSION_ID}) and is not enough to restore a session. Previously
     * the resume command stood only in the prose of the description, because an agent wrote it
     * there - prompt-enforced instead of backend-enforced, and accordingly patchy
     * (63 % of Claude Code TODOs, measured 2026-09-16). V76 carries the existing values over
     * once from the descriptions (TODO 3497533c).
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

    // ── Assignment to a goal (TODO dc0566f8) ──────────────────────────────────

    /** Goal this TODO contributes to; at most one. */
    @Column(name = "goal_id")
    private String goalId;

    /** Position in the goal, 1 = first; null = not yet ranked (goes last). */
    @Column(name = "goal_rank")
    private Integer goalRank;

    /**
     * Work package of the goal this TODO is in (TODO c60bf914, until V92 "milestone"); only
     * within {@link #goalId} and - checked on write - the same project.
     */
    @Column(name = "work_package_id")
    private String workPackageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "complexity")
    private Complexity complexity;

    /**
     * Recommended reasoning effort ({@code low|medium|high|xhigh}), as text like
     * {@code Workflow.effort} - the entity does not depend on the service package; it is checked
     * against {@code ReasoningEffort} on write.
     */
    @Column(name = "suggested_effort")
    private String suggestedEffort;

    /** One to two sentences on why complexity and effort are estimated this way. */
    @Column(name = "rating_note", columnDefinition = "text")
    private String ratingNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "rated_by")
    private RatedBy ratedBy;

    @Column(name = "rated_at")
    private Instant ratedAt;

    /** Question that must be clarified before the work; null = nothing open. */
    @Column(name = "open_question", columnDefinition = "text")
    private String openQuestion;

    /**
     * Decision that answered an open question (TODO 68a5e24e); null = none. A
     * decision clears the open question, a later new question leaves it standing.
     */
    @Column(name = "decision", columnDefinition = "text")
    private String decision;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by")
    private RatedBy decidedBy;

    /** State from open question and decision - never stored (TODO 68a5e24e). */
    public DecisionState getDecisionState() {
        return DecisionState.of(openQuestion, decision);
    }

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /**
     * A TODO has just changed to {@code DONE}. Spring Data publishes the event on the next
     * save/saveAll synchronously in the caller's transaction; the consequence - e.g. an audit finding to
     * AWAITING_VERIFICATION - thus hangs on no single write path. Here instead of in {@code event}, because
     * {@code event} already depends on {@code entity} (no package cycle).
     */
    public record Completed(Todo todo) {
    }

    /**
     * Several TODOs changed to {@code DONE} via bulk UPDATE ({@code TodoRepository.bulkSetStatus}).
     * The UPDATE loads no entities, so none reports itself ({@link Completed}); the caller
     * publishes the IDs of the TODOs that actually changed collectively.
     */
    public record CompletedInBulk(java.util.List<String> todoIds) {
    }

    /**
     * A change to {@code DONE} reports itself as {@link Completed} - once per change, not
     * on DONE -> DONE (follow-up finding to ab1ed481). Hibernate loads via field access, so loading triggers
     * nothing.
     */
    public void setStatus(Status status) {
        if (status == Status.DONE && this.status != Status.DONE) {
            domainEvents.add(new Completed(this));
        }
        this.status = status;
    }

    /** For Spring Data: published on this TODO's save/saveAll. */
    @DomainEvents
    public List<Object> domainEvents() {
        return List.copyOf(domainEvents);
    }

    @AfterDomainEventPublication
    public void clearDomainEvents() {
        domainEvents.clear();
    }
}
