package com.automation.repository;

import com.automation.entity.ScheduledWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledWorkflowRepository extends JpaRepository<ScheduledWorkflow, String> {

    List<ScheduledWorkflow> findByEnabledTrue();

    @Query("SELECT s FROM ScheduledWorkflow s WHERE s.enabled = true AND s.nextRunAt <= :before")
    List<ScheduledWorkflow> findDueSchedules(@Param("before") Instant before);

    /**
     * Loads a schedule with its workflow's activatedSkills eagerly fetched via JOIN FETCH.
     * Use this in the scheduler executor to avoid LazyInitializationException when
     * WorkflowExecutor.execute() accesses activatedSkills outside a JPA session.
     */
    @Query("SELECT sw FROM ScheduledWorkflow sw " +
           "LEFT JOIN FETCH sw.workflow w " +
           "LEFT JOIN FETCH w.activatedSkills " +
           "WHERE sw.id = :id")
    Optional<ScheduledWorkflow> findByIdWithWorkflowSkills(@Param("id") String id);

    /**
     * Alle Zeitplaene samt ihren Zielen in einer Abfrage — die Liste hinter
     * {@code GET /api/v1/schedules}.
     *
     * <p>{@code findAll()} laesst die beiden {@code EAGER}-Assoziationen
     * {@code workflow} und {@code chain} offen und Hibernate loest sie danach Zeile fuer Zeile
     * auf: bei 30 Zeitplaenen 30 zusaetzliche Abfragen, jede davon auf der einzigen
     * SQLite-Connection (TODO c9ec9844). Der Join holt sie mit.
     *
     * <p>{@code w.project} und {@code p.container} haengen an derselben Kette: Sie sind ihrerseits
     * {@code EAGER} und wuerden sonst je Projekt eine weitere Abfrage kosten. Mitgeholt bleibt die
     * Zahl der Abfragen konstant, unabhaengig von der Zahl der Zeitplaene.
     *
     * <p>{@code LEFT}, nicht {@code INNER}: Ein Zeitplan hat entweder einen Workflow oder eine
     * Kette, nie beides — und ein Zeitplan, dessen Workflow soft-geloescht wurde
     * ({@code Workflow} traegt {@code @SQLRestriction("deleted_at IS NULL")}), bleibt mit leerem
     * Ziel sichtbar statt die ganze Liste mitzureissen. Genau dieses Verhalten haelt
     * {@code ScheduleSoftDeletedWorkflowIntegrationTest} fest.
     */
    @Query("SELECT s FROM ScheduledWorkflow s " +
           "LEFT JOIN FETCH s.workflow w " +
           "LEFT JOIN FETCH w.project p " +
           "LEFT JOIN FETCH p.container " +
           "LEFT JOIN FETCH s.chain")
    List<ScheduledWorkflow> findAllWithTargets();

    List<ScheduledWorkflow> findByWorkflowId(String workflowId);

    List<ScheduledWorkflow> findByChainId(String chainId);

    @Query("SELECT s FROM ScheduledWorkflow s WHERE s.enabled = true AND s.nextRunAt BETWEEN :from AND :to")
    List<ScheduledWorkflow> findSchedulesInRange(@Param("from") Instant from, @Param("to") Instant to);
}
