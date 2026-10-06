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
     * The non-archived projects together with their container — the list behind
     * {@code GET /api/v1/projects}.
     *
     * <p>{@code Project.container} is a nullable {@code @OneToOne(mappedBy = "project")}. Hibernate
     * cannot create such an association as a proxy (only the query knows whether a row exists), so it resolves
     * {@code findByArchivedAtIsNull()} individually per materialized project. With three projects this goes
     * unnoticed, on the dev database it was
     * 37 statements for one list (TODO 6d0ab09f). The join fetches the container along; the number of
     * queries stays constant, independent of the number of projects.
     */
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.container WHERE p.archivedAt IS NULL")
    List<Project> findActiveWithContainer();

    List<Project> findByArchivedAtIsNotNull();

    /**
     * Id and name per project in one query, without an entity — and thus without the
     * {@code select containers} per project that {@code findAllById} triggers (TODO c6ea8f5c).
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
