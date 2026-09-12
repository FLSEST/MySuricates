package com.automation.repository;

import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContainerRepository extends JpaRepository<Container, String> {

    Optional<Container> findByProjectId(String projectId);

    Optional<Container> findByDockerContainerId(String dockerContainerId);

    List<Container> findByStatus(ContainerStatus status);

    /**
     * Stamps the container's last activity in a single UPDATE. Called once per workflow step from
     * outside any transaction — loading the entity and saving it back would detach it, force a
     * merge (SELECT + UPDATE) and drag the eagerly mapped Project/Container graph along, all on
     * the single SQLite connection.
     *
     * <p>Callers must be non-transactional (as {@code WorkflowExecutor} is): the update bypasses
     * the persistence context, so a transactional caller holding the same {@code Container} would
     * keep reading a stale {@code lastActivityAt}. Deliberately no {@code clearAutomatically} —
     * that would detach every other entity the caller is working with. A future transactional
     * caller should isolate the write instead ({@code REQUIRES_NEW} or a short
     * {@code TransactionTemplate} burst) and re-read the container afterwards.
     *
     * @return the number of rows updated (0 when the Docker container id is unknown)
     */
    @Modifying
    @Transactional
    @Query("update Container c set c.lastActivityAt = :now where c.dockerContainerId = :id")
    int touchActivity(@Param("id") String dockerContainerId, @Param("now") Instant now);
}
