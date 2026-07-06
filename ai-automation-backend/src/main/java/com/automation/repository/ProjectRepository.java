package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findByArchivedAtIsNull();

    List<Project> findByArchivedAtIsNotNull();

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
