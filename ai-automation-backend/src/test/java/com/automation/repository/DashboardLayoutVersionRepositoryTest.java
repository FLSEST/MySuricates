package com.automation.repository;

import com.automation.entity.DashboardLayoutVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class DashboardLayoutVersionRepositoryTest {

    private static final Instant BASE = Instant.parse("2026-09-15T10:00:00Z");

    @Autowired
    private DashboardLayoutVersionRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void lists_versions_newest_first() {
        save("old", BASE, null);
        save("new", BASE.plusSeconds(60), null);

        Page<DashboardLayoutVersionListItem> page =
                repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(DashboardLayoutVersionListItem::getDeviceLabel)
                .containsExactly("new", "old");
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getContentHash()).isEqualTo("hash-new");
        assertThat(repository.findFirstByOrderByCreatedAtDescIdDesc())
                .map(DashboardLayoutVersionListItem::getDeviceLabel).contains("new");
    }

    @Test
    void has_no_latest_version_when_empty() {
        assertThat(repository.findFirstByOrderByCreatedAtDescIdDesc()).isEmpty();
    }

    @Test
    void prune_keeps_the_newest_unnamed_versions_and_every_named_one() {
        save("unnamed-1", BASE, null);
        save("named", BASE.plusSeconds(10), "vor dem Umbau");
        save("unnamed-2", BASE.plusSeconds(20), null);
        save("unnamed-3", BASE.plusSeconds(30), null);

        int deleted = repository.pruneUnnamed(2);

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findAll()).extracting(DashboardLayoutVersion::getDeviceLabel)
                .containsExactlyInAnyOrder("named", "unnamed-2", "unnamed-3");
    }

    private void save(String deviceLabel, Instant createdAt, String name) {
        repository.saveAndFlush(DashboardLayoutVersion.builder()
                .createdAt(createdAt)
                .deviceLabel(deviceLabel)
                .name(name)
                .formatVersion(2)
                .viewCount(1)
                .contentHash("hash-" + deviceLabel)
                .content("{}")
                .build());
    }
}
