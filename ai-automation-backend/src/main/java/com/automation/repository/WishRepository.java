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

    /** Wuensche, die auf einen Run zeigen — loser Textverweis, beim Loeschen zu nullen. */
    List<Wish> findByCurrentRunId(String currentRunId);
}
