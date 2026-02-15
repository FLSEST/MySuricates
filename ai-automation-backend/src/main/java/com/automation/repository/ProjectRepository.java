package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findByStatus(ProjectStatus status);

    List<Project> findByNameContainingIgnoreCase(String name);

    boolean existsByPath(String path);
}
