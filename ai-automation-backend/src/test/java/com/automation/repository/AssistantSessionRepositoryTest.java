package com.automation.repository;

import com.automation.entity.AssistantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AssistantSessionRepositoryTest {

    @Autowired
    private AssistantSessionRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private AssistantSession createSession(String page, Instant startedAt) {
        return AssistantSession.builder()
                .id(UUID.randomUUID().toString())
                .page(page)
                .startedAt(startedAt)
                .messages("[]")
                .messageCount(0)
                .build();
    }

    // ---------- findTop10ByPageOrderByStartedAtDesc ----------

    @Test
    void should_return_sessions_for_given_page_ordered_desc_when_findTop10ByPage() {
        Instant now = Instant.now();
        AssistantSession s1 = createSession("workflows", now.minusSeconds(30));
        AssistantSession s2 = createSession("workflows", now.minusSeconds(10));
        AssistantSession s3 = createSession("workflows", now.minusSeconds(20));
        AssistantSession other = createSession("chains", now);
        repository.save(s1);
        repository.save(s2);
        repository.save(s3);
        repository.save(other);

        List<AssistantSession> result = repository.findTop10ByPageOrderByStartedAtDesc("workflows");

        assertThat(result).hasSize(3);
        assertThat(result).extracting(AssistantSession::getId)
                .containsExactly(s2.getId(), s3.getId(), s1.getId());
        assertThat(result).allMatch(s -> "workflows".equals(s.getPage()));
    }

    @Test
    void should_limit_to_10_sessions_when_findTop10ByPage() {
        Instant now = Instant.now();
        for (int i = 0; i < 15; i++) {
            repository.save(createSession("workflows", now.minusSeconds(i)));
        }

        List<AssistantSession> result = repository.findTop10ByPageOrderByStartedAtDesc("workflows");

        assertThat(result).hasSize(10);
    }

    @Test
    void should_return_empty_list_when_findTop10ByPage_and_no_match() {
        repository.save(createSession("chains", Instant.now()));

        List<AssistantSession> result = repository.findTop10ByPageOrderByStartedAtDesc("workflows");

        assertThat(result).isEmpty();
    }

    // ---------- findTop3ByOrderByStartedAtDesc ----------

    @Test
    void should_return_latest_3_sessions_when_findTop3() {
        Instant now = Instant.now();
        AssistantSession s1 = createSession("a", now.minusSeconds(50));
        AssistantSession s2 = createSession("b", now.minusSeconds(40));
        AssistantSession s3 = createSession("c", now.minusSeconds(30));
        AssistantSession s4 = createSession("d", now.minusSeconds(20));
        AssistantSession s5 = createSession("e", now.minusSeconds(10));
        repository.save(s1);
        repository.save(s2);
        repository.save(s3);
        repository.save(s4);
        repository.save(s5);

        List<AssistantSession> result = repository.findTop3ByOrderByStartedAtDesc();

        assertThat(result).hasSize(3);
        assertThat(result).extracting(AssistantSession::getId)
                .containsExactly(s5.getId(), s4.getId(), s3.getId());
    }

    @Test
    void should_return_empty_list_when_findTop3_and_no_data() {
        List<AssistantSession> result = repository.findTop3ByOrderByStartedAtDesc();

        assertThat(result).isEmpty();
    }

    // ---------- findTop5ByOrderByStartedAtDesc ----------

    @Test
    void should_return_latest_5_sessions_when_findTop5() {
        Instant now = Instant.now();
        for (int i = 0; i < 7; i++) {
            repository.save(createSession("page" + i, now.minusSeconds(i * 10L)));
        }

        List<AssistantSession> result = repository.findTop5ByOrderByStartedAtDesc();

        assertThat(result).hasSize(5);
        // Newest first: i=0 is newest
        assertThat(result.get(0).getStartedAt()).isAfterOrEqualTo(result.get(4).getStartedAt());
    }

    @Test
    void should_return_all_when_findTop5_and_less_than_5_exist() {
        Instant now = Instant.now();
        repository.save(createSession("a", now.minusSeconds(20)));
        repository.save(createSession("b", now.minusSeconds(10)));

        List<AssistantSession> result = repository.findTop5ByOrderByStartedAtDesc();

        assertThat(result).hasSize(2);
    }

    // ---------- countByPage ----------

    @Test
    void should_count_sessions_for_page_when_countByPage() {
        Instant now = Instant.now();
        repository.save(createSession("workflows", now));
        repository.save(createSession("workflows", now.minusSeconds(10)));
        repository.save(createSession("workflows", now.minusSeconds(20)));
        repository.save(createSession("chains", now));

        long count = repository.countByPage("workflows");

        assertThat(count).isEqualTo(3);
    }

    @Test
    void should_return_zero_when_countByPage_and_no_match() {
        repository.save(createSession("chains", Instant.now()));

        long count = repository.countByPage("workflows");

        assertThat(count).isZero();
    }

    // ---------- findTop50ByOrderByStartedAtDesc ----------

    @Test
    void should_return_latest_50_sessions_when_findTop50() {
        Instant now = Instant.now();
        for (int i = 0; i < 60; i++) {
            repository.save(createSession("p", now.minusSeconds(i)));
        }

        List<AssistantSession> result = repository.findTop50ByOrderByStartedAtDesc();

        assertThat(result).hasSize(50);
        // Sorted desc
        for (int i = 0; i < result.size() - 1; i++) {
            assertThat(result.get(i).getStartedAt())
                    .isAfterOrEqualTo(result.get(i + 1).getStartedAt());
        }
    }

    @Test
    void should_return_empty_list_when_findTop50_and_no_data() {
        List<AssistantSession> result = repository.findTop50ByOrderByStartedAtDesc();

        assertThat(result).isEmpty();
    }

    // ---------- Save/Retrieve basics ----------

    @Test
    void should_persist_and_retrieve_session_by_id() {
        AssistantSession session = createSession("workflows", Instant.now());
        session.setEntityId("entity-123");
        session.setPreview("Hello");
        session.setMessageCount(5);
        repository.save(session);

        AssistantSession found = repository.findById(session.getId()).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getPage()).isEqualTo("workflows");
        assertThat(found.getEntityId()).isEqualTo("entity-123");
        assertThat(found.getPreview()).isEqualTo("Hello");
        assertThat(found.getMessageCount()).isEqualTo(5);
    }
}
