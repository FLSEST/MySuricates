package com.automation.config;

import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for JpaConfig.
 * Verifies that @EnableJpaAuditing populates @CreatedDate and @LastModifiedDate fields.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class JpaConfigIntegrationTest {

    @Autowired
    private TodoRepository todoRepository;

    @Test
    void createdDate_isPopulatedOnSave() {
        Todo todo = Todo.builder()
                .title("Audit test")
                .build();

        Todo saved = todoRepository.save(todo);

        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void lastModifiedDate_isPopulatedOnSave() {
        Todo todo = Todo.builder()
                .title("Audit test")
                .build();

        Todo saved = todoRepository.save(todo);

        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void lastModifiedDate_updatesOnModification() {
        Todo todo = todoRepository.save(Todo.builder()
                .title("Audit test")
                .build());

        var createdAt = todo.getCreatedAt();
        var initialUpdatedAt = todo.getUpdatedAt();

        todo.setTitle("Updated title");
        todoRepository.saveAndFlush(todo);

        Todo reloaded = todoRepository.findById(todo.getId()).orElseThrow();

        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(initialUpdatedAt);
    }
}
