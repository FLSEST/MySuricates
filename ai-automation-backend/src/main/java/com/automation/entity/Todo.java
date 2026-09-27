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
    /** Umfang und Unsicherheit einer Aufgabe – grob, damit Mensch und KI dieselbe Skala nutzen. */
    public enum Complexity { S, M, L, XL }
    /** Wer die Einschaetzung zuletzt gesetzt hat; ein Mensch ueberschreibt die KI. */
    public enum RatedBy { USER, AI }

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

    // ── Einordnung in ein Ziel (TODO dc0566f8) ────────────────────────────────

    /** Ziel, auf das dieses TODO einzahlt; hoechstens eins. */
    @Column(name = "goal_id")
    private String goalId;

    /** Position im Ziel, 1 = zuerst; null = noch nicht eingeordnet (steht am Ende). */
    @Column(name = "goal_rank")
    private Integer goalRank;

    /**
     * Arbeitspaket des Ziels, in dem dieses TODO steht (TODO c60bf914, bis V92 „Meilenstein“); nur
     * innerhalb von {@link #goalId} und – beim Schreiben geprueft – desselben Projekts.
     */
    @Column(name = "work_package_id")
    private String workPackageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "complexity")
    private Complexity complexity;

    /**
     * Empfohlener Reasoning-Effort ({@code low|medium|high|xhigh}), als Text wie
     * {@code Workflow.effort} – die Entity haengt nicht vom Service-Paket ab; geprueft wird
     * gegen {@code ReasoningEffort} beim Schreiben.
     */
    @Column(name = "suggested_effort")
    private String suggestedEffort;

    /** Ein bis zwei Saetze, warum Komplexitaet und Effort so eingeschaetzt sind. */
    @Column(name = "rating_note", columnDefinition = "text")
    private String ratingNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "rated_by")
    private RatedBy ratedBy;

    @Column(name = "rated_at")
    private Instant ratedAt;

    /** Frage, die vor der Arbeit geklaert sein muss; null = nichts offen. */
    @Column(name = "open_question", columnDefinition = "text")
    private String openQuestion;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
