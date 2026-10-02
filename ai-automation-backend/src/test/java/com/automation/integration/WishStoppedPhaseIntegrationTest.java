package com.automation.integration;

import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.WishRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V97 (TODO 92bffe64): the phase a wish stopped in is stored with it, against real SQLite — a reload gets it
 * back, and loading does not run {@code Wish.setStatus} (field access), so it does not overwrite it.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WishStoppedPhaseIntegrationTest {

    @Autowired private WishRepository wishRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void stoppedPhase_survivesAReload() {
        Wish wish = wishRepository.save(Wish.builder().originalText("Bericht bauen").build());
        wish.setStatus(WishStatus.EXECUTING);
        wishRepository.save(wish);
        wish.setStatus(WishStatus.FAILED);
        wishRepository.save(wish);
        String running = wishRepository.save(Wish.builder().originalText("läuft").build()).getId();
        entityManager.flush();
        entityManager.clear();

        Wish failed = wishRepository.findById(wish.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(WishStatus.FAILED);
        assertThat(failed.getStoppedPhase()).isEqualTo(WishStatus.EXECUTING);
        assertThat(wishRepository.findById(running).orElseThrow().getStoppedPhase()).isNull();
    }
}
