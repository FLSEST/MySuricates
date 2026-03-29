package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that stable_synced_at can be reliably updated via different approaches
 * under production-like constraints: HikariCP pool-size=1, OSIV enabled, SQLite.
 *
 * NOT @Transactional — we need real commits to verify persistence.
 */
@SpringBootTest
@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StableSyncedAtIntegrationTest {

    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private DataSource dataSource;

    // Match production: pool-size=1, OSIV off
    @DynamicPropertySource
    static void overridePoolSize(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    private String projectId;
    private String workflowId;

    @BeforeEach
    void setup() {
        // Create test project + workflow
        Project project = projectRepository.save(Project.builder()
                .name("StableSync Test " + System.currentTimeMillis())
                .type(ProjectType.CODE)
                .path("/tmp/stable-sync-test")
                .status(ProjectStatus.RUNNING)
                .build());
        projectId = project.getId();

        Workflow workflow = Workflow.builder()
                .name("Test Workflow " + System.currentTimeMillis())
                .project(project)
                .blocks(new ArrayList<>())
                .build();
        workflow = workflowRepository.save(workflow);
        workflowId = workflow.getId();
    }

    @AfterEach
    void cleanup() {
        // Raw JDBC cleanup to avoid Hibernate interference
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM workflows WHERE id = ?")) {
            ps.setString(1, workflowId);
            ps.executeUpdate();
        } catch (Exception e) { /* ignore */ }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM projects WHERE id = ?")) {
            ps.setString(1, projectId);
            ps.executeUpdate();
        } catch (Exception e) { /* ignore */ }
    }

    @Test
    @Order(1)
    void approach1_modifyingNativeQuery_afterFindAll() {
        // This is the original approach: load all via findAll(), then native update
        List<Workflow> workflows = workflowRepository.findAll();
        assertThat(workflows).isNotEmpty();

        // Now try the @Modifying @Transactional native update
        workflowRepository.updateStableSyncedAt(workflowId, Instant.now().toString());

        // Verify via raw JDBC (bypasses Hibernate cache)
        String value = readStableSyncedAtRaw(workflowId);
        assertThat(value)
                .as("@Modifying native query after findAll() should persist stable_synced_at")
                .isNotNull();
    }

    @Test
    @Order(2)
    void approach2_modifyingNativeQuery_afterFindAllReadOnly() {
        // Load as read-only to prevent dirty-check flushes
        List<Workflow> workflows = workflowRepository.findAllReadOnly();
        assertThat(workflows).isNotEmpty();

        workflowRepository.updateStableSyncedAt(workflowId, Instant.now().toString());

        String value = readStableSyncedAtRaw(workflowId);
        assertThat(value)
                .as("@Modifying native query after findAllReadOnly() should persist stable_synced_at")
                .isNotNull();
    }

    @Test
    @Order(3)
    void approach3_rawJdbc_afterFindAll() {
        // Load via findAll, then use raw JDBC
        List<Workflow> workflows = workflowRepository.findAll();
        assertThat(workflows).isNotEmpty();

        updateStableSyncedAtRaw(workflowId, Instant.now().toString());

        String value = readStableSyncedAtRaw(workflowId);
        assertThat(value)
                .as("Raw JDBC after findAll() should persist stable_synced_at")
                .isNotNull();
    }

    @Test
    @Order(4)
    void approach4_rawJdbc_afterFindAllReadOnly() {
        // Load as read-only, then use raw JDBC
        List<Workflow> workflows = workflowRepository.findAllReadOnly();
        assertThat(workflows).isNotEmpty();

        updateStableSyncedAtRaw(workflowId, Instant.now().toString());

        String value = readStableSyncedAtRaw(workflowId);
        assertThat(value)
                .as("Raw JDBC after findAllReadOnly() should persist stable_synced_at")
                .isNotNull();
    }

    @Test
    @Order(5)
    void approach5_rawJdbc_standalone() {
        // Baseline: raw JDBC without any prior Hibernate call
        updateStableSyncedAtRaw(workflowId, Instant.now().toString());

        String value = readStableSyncedAtRaw(workflowId);
        assertThat(value)
                .as("Raw JDBC standalone should persist stable_synced_at")
                .isNotNull();
    }

    // ── Helpers ─────────────────────────────────────────

    private void updateStableSyncedAtRaw(String id, String syncedAt) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE workflows SET stable_synced_at = ? WHERE id = ?")) {
            ps.setString(1, syncedAt);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Raw JDBC update failed: " + e.getMessage(), e);
        }
    }

    private String readStableSyncedAtRaw(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT stable_synced_at FROM workflows WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("Raw JDBC read failed: " + e.getMessage(), e);
        }
        return null;
    }
}
