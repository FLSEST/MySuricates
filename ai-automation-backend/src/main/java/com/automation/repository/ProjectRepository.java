package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findByStatus(ProjectStatus status);

    List<Project> findByNameContainingIgnoreCase(String name);

    Optional<Project> findFirstByName(String name);

    boolean existsByPath(String path);

    @Query(value = "SELECT * FROM projects WHERE archived_at IS NOT NULL", nativeQuery = true)
    List<Project> findArchived();

    @Query(value = "SELECT * FROM projects WHERE id = :id", nativeQuery = true)
    Optional<Project> findByIdIncludingArchived(@Param("id") String id);
}
