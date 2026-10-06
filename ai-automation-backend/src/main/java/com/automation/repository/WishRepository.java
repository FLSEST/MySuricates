package com.automation.repository;

import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface WishRepository extends JpaRepository<Wish, String> {
    List<Wish> findByProjectIdOrderByCreatedAtDesc(String projectId);
    List<Wish> findByStatusOrderByCreatedAtDesc(WishStatus status);
    List<Wish> findAllByOrderByCreatedAtDesc();
    long countByStatusIn(List<WishStatus> statuses);
    List<Wish> findByStatusInAndUpdatedAtBefore(List<WishStatus> statuses, Instant before);

    /** Wishes that point to a run — loose text reference, to be nulled on deletion. */
    List<Wish> findByCurrentRunId(String currentRunId);
}
