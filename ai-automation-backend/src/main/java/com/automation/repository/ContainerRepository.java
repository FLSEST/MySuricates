package com.automation.repository;

import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContainerRepository extends JpaRepository<Container, String> {

    Optional<Container> findByProjectId(String projectId);

    Optional<Container> findByDockerContainerId(String dockerContainerId);

    List<Container> findByStatus(ContainerStatus status);
}
