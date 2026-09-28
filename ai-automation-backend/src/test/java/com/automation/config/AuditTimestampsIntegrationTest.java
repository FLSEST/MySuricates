package com.automation.config;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.Todo;
import com.automation.repository.SkillRepository;
import com.automation.repository.TodoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the behaviour of the {@code createdAt}/{@code updatedAt} columns of the entities.
 *
 * <p>Written against Spring Data auditing and kept unchanged when the entities moved to the own
 * {@link com.automation.entity.AuditTimestampListener} (TODO dc3d6101): Spring Data auditing needs
 * spring-aspects and with it aspectjweaver (EPL-2.0), which does not go together with the AGPL.
 * Hibernate's {@code @CreationTimestamp}/{@code @UpdateTimestamp} failed these tests — set only at
 * flush and truncated to whole seconds. Covers both save paths — {@code persist} for generated
 * ids ({@link Todo}) and {@code merge} for assigned ids ({@link Skill}).
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditTimestampsIntegrationTest {

    /** Long enough that two {@link Instant#now()} calls are guaranteed to differ. */
    private static final long TICK_MS = 20;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private EntityManager entityManager;

    // ---- generated id: persist path ----

    @Test
    void persist_setsBothTimestamps_beforeFlush() {
        Instant before = Instant.now();

        Todo saved = todoRepository.save(Todo.builder().title("Audit test").build());

        assertThat(saved.getCreatedAt()).isNotNull().isAfterOrEqualTo(before.minusMillis(1));
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(Duration.between(saved.getCreatedAt(), saved.getUpdatedAt()).abs())
                .isLessThan(Duration.ofSeconds(1));
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
    }

    @Test
    void persist_writesTimestampsToTheDatabase() {
        Todo saved = todoRepository.saveAndFlush(Todo.builder().title("Audit test").build());
        Instant createdAt = saved.getCreatedAt();
        Instant updatedAt = saved.getUpdatedAt();
        entityManager.clear();

        Todo reloaded = todoRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void persist_overwritesCreatedAtSetByTheCaller() {
        Instant preset = Instant.parse("2020-01-01T00:00:00Z");

        Todo saved = todoRepository.saveAndFlush(Todo.builder().title("Audit test")
                .createdAt(preset).updatedAt(preset).build());

        assertThat(saved.getCreatedAt()).isAfter(preset);
        assertThat(saved.getUpdatedAt()).isAfter(preset);
    }

    @Test
    void update_bumpsUpdatedAt_andKeepsCreatedAt() throws InterruptedException {
        Todo todo = todoRepository.saveAndFlush(Todo.builder().title("Audit test").build());
        Instant createdAt = todo.getCreatedAt();
        Instant initialUpdatedAt = todo.getUpdatedAt();
        Thread.sleep(TICK_MS);

        todo.setTitle("Updated title");
        todoRepository.saveAndFlush(todo);
        entityManager.clear();

        Todo reloaded = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isAfter(initialUpdatedAt);
    }

    @Test
    void flushWithoutChange_leavesUpdatedAtAlone() throws InterruptedException {
        Todo todo = todoRepository.saveAndFlush(Todo.builder().title("Audit test").build());
        Instant updatedAt = todo.getUpdatedAt();
        Thread.sleep(TICK_MS);

        todoRepository.saveAndFlush(todo);
        entityManager.clear();

        assertThat(todoRepository.findById(todo.getId()).orElseThrow().getUpdatedAt())
                .isEqualTo(updatedAt);
    }

    @Test
    void createdAt_isNotUpdatable_evenWhenChangedOnTheEntity() throws InterruptedException {
        Todo todo = todoRepository.saveAndFlush(Todo.builder().title("Audit test").build());
        Instant createdAt = todo.getCreatedAt();
        Thread.sleep(TICK_MS);

        todo.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        todo.setTitle("Updated title");
        todoRepository.saveAndFlush(todo);
        entityManager.clear();

        assertThat(todoRepository.findById(todo.getId()).orElseThrow().getCreatedAt())
                .isEqualTo(createdAt);
    }

    // ---- assigned id: merge path ----

    @Test
    void merge_ofNewEntityWithAssignedId_setsBothTimestamps() {
        String id = "audit-test-" + UUID.randomUUID();

        Skill saved = skillRepository.saveAndFlush(skill(id, "Audit skill"));
        entityManager.clear();

        Skill reloaded = skillRepository.findById(id).orElseThrow();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
    }

    @Test
    void merge_ofDetachedCopyWithoutTimestamps_keepsCreatedAt_andBumpsUpdatedAt()
            throws InterruptedException {
        String id = "audit-test-" + UUID.randomUUID();
        Skill original = skillRepository.saveAndFlush(skill(id, "Audit skill"));
        Instant createdAt = original.getCreatedAt();
        Instant initialUpdatedAt = original.getUpdatedAt();
        entityManager.clear();
        Thread.sleep(TICK_MS);

        // a detached copy as a mapper would build it: same id, no timestamps
        skillRepository.saveAndFlush(skill(id, "Renamed skill"));
        entityManager.clear();

        Skill reloaded = skillRepository.findById(id).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Renamed skill");
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isAfter(initialUpdatedAt);
    }

    private static Skill skill(String id, String name) {
        return Skill.builder()
                .id(id)
                .name(name)
                .category(SkillCategory.values()[0])
                .build();
    }
}
