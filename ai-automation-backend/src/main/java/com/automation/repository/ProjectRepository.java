package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findByArchivedAtIsNull();

    long countByArchivedAtIsNull();

    long countByArchivedAtIsNotNull();

    /**
     * Die nicht archivierten Projekte samt Container — die Liste hinter
     * {@code GET /api/v1/projects}.
     *
     * <p>{@code Project.container} ist eine nullable {@code @OneToOne(mappedBy = "project")}. Eine
     * solche Assoziation kann Hibernate nicht als Proxy anlegen (ob es eine Zeile gibt, weiss erst
     * die Abfrage), also loest sie {@code findByArchivedAtIsNull()} je materialisiertem Projekt
     * einzeln auf. Bei drei Projekten faellt das nicht auf, auf der Dev-Datenbank waren es
     * 37 Statements fuer eine Liste (TODO 6d0ab09f). Der Join holt den Container mit; die Zahl der
     * Abfragen bleibt konstant, unabhaengig von der Zahl der Projekte.
     */
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.container WHERE p.archivedAt IS NULL")
    List<Project> findActiveWithContainer();

    List<Project> findByArchivedAtIsNotNull();

    /**
     * Id und Name je Projekt in einer Abfrage, ohne Entity – und damit ohne das
     * {@code select containers} je Projekt, das {@code findAllById} ausloest (TODO c6ea8f5c).
     */
    @Query("SELECT p.id, p.name FROM Project p WHERE p.id IN :ids")
    List<Object[]> findIdAndNameByIdIn(@org.springframework.data.repository.query.Param("ids") Collection<String> ids);

    List<Project> findByStatus(ProjectStatus status);

    List<Project> findByNameContainingIgnoreCase(String name);

    /**
     * Projects whose id starts with the given prefix. Used to recover a full project
     * UUID when a caller (notably the chat assistant) supplies only the shortened
     * 8-char display prefix of an id — see {@code TodoService#resolveProjectId}.
     */
    List<Project> findByIdStartingWith(String prefix);

    Optional<Project> findFirstByName(String name);

    boolean existsByPath(String path);
}
