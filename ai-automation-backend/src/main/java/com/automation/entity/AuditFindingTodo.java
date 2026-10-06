package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;

import java.time.Instant;

@Entity
@Table(name = "audit_finding_todos", uniqueConstraints =
        @UniqueConstraint(name = "uq_audit_finding_todo", columnNames = {"finding_id", "todo_id"}))
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditFindingTodo {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(name = "finding_id", nullable = false)
    private String findingId;
    @Column(name = "todo_id", nullable = false)
    private String todoId;
    @Column(nullable = false)
    private boolean managed;

    /**
     * A managed TODO created by hand (Audit Center button) rather than by the ingest threshold. Only automatic
     * TODOs are released when their open findings fall below HIGH (f2ce6c50).
     */
    @Column(nullable = false)
    private boolean manual;
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
