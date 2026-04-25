package com.automation.repository;

import com.automation.entity.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class TemplateVerificationRepositoryTest {

    @Autowired
    private TemplateVerificationRepository repository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkflowTemplateRepository templateRepository;

    @Test
    void findByTemplateId_returnsVerifications_whenTemplateExists() {
        String templateId = saveTemplate().getId();
        String projectId = saveProject().getId();
        TemplateVerification saved = persistVerification(templateId, projectId);

        List<TemplateVerification> found = repository.findByTemplateId(templateId);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(saved.getId());
        assertThat(found.get(0).getTemplateId()).isEqualTo(templateId);
    }

    @Test
    void findByTemplateId_returnsEmptyList_whenTemplateDoesNotExist() {
        List<TemplateVerification> found = repository.findByTemplateId("missing-" + System.nanoTime());

        assertThat(found).isEmpty();
    }

    @Test
    void findByTemplateId_returnsAllMatching_whenMultipleProjectsVerifiedSameTemplate() {
        String templateId = saveTemplate().getId();
        persistVerification(templateId, saveProject().getId());
        persistVerification(templateId, saveProject().getId());
        persistVerification(saveTemplate().getId(), saveProject().getId());

        List<TemplateVerification> found = repository.findByTemplateId(templateId);

        assertThat(found).hasSize(2);
        assertThat(found).allMatch(v -> v.getTemplateId().equals(templateId));
    }

    @Test
    void findByTemplateIdAndProjectId_returnsVerification_whenBothMatch() {
        String templateId = saveTemplate().getId();
        String projectId = saveProject().getId();
        TemplateVerification saved = persistVerification(templateId, projectId);

        Optional<TemplateVerification> found = repository.findByTemplateIdAndProjectId(templateId, projectId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getTemplateId()).isEqualTo(templateId);
        assertThat(found.get().getProjectId()).isEqualTo(projectId);
    }

    @Test
    void findByTemplateIdAndProjectId_returnsEmpty_whenTemplateDoesNotExist() {
        Optional<TemplateVerification> found = repository.findByTemplateIdAndProjectId(
                "missing-template-" + System.nanoTime(),
                "project-" + System.nanoTime());

        assertThat(found).isEmpty();
    }

    @Test
    void findByTemplateIdAndProjectId_returnsEmpty_whenProjectDoesNotMatch() {
        String templateId = saveTemplate().getId();
        persistVerification(templateId, saveProject().getId());

        Optional<TemplateVerification> found = repository.findByTemplateIdAndProjectId(
                templateId,
                "project-b-" + System.nanoTime());

        assertThat(found).isEmpty();
    }

    @Test
    void findByTemplateIdAndProjectId_returnsCorrectOne_whenMultipleVerificationsExist() {
        String templateId = saveTemplate().getId();
        String targetProjectId = saveProject().getId();
        persistVerification(templateId, saveProject().getId());
        TemplateVerification target = persistVerification(templateId, targetProjectId);

        Optional<TemplateVerification> found = repository.findByTemplateIdAndProjectId(templateId, targetProjectId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(target.getId());
        assertThat(found.get().getProjectId()).isEqualTo(targetProjectId);
    }

    @Test
    void save_persistsVerificationFields() {
        String templateId = saveTemplate().getId();
        String projectId = saveProject().getId();
        Instant verifiedAt = Instant.now();

        TemplateVerification verification = TemplateVerification.builder()
                .templateId(templateId)
                .projectId(projectId)
                .verifiedAt(verifiedAt)
                .build();
        TemplateVerification saved = repository.saveAndFlush(verification);

        assertThat(saved.getId()).isNotBlank();
        Optional<TemplateVerification> reloaded = repository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getTemplateId()).isEqualTo(templateId);
        assertThat(reloaded.get().getProjectId()).isEqualTo(projectId);
        assertThat(reloaded.get().getLastRunId()).isNull();
        assertThat(reloaded.get().getVerifiedAt()).isEqualTo(verifiedAt);
    }

    private Project saveProject() {
        return projectRepository.save(Project.builder()
                .name("Test Project " + System.nanoTime())
                .type(ProjectType.CODE)
                .path("/tmp/test-" + System.nanoTime())
                .status(ProjectStatus.STOPPED)
                .build());
    }

    private WorkflowTemplate saveTemplate() {
        return templateRepository.save(WorkflowTemplate.builder()
                .name(Map.of("de", "Test Template " + System.nanoTime()))
                .blocks(List.of(BlockDefinition.builder().type("simple").build()))
                .build());
    }

    private TemplateVerification persistVerification(String templateId, String projectId) {
        return repository.saveAndFlush(TemplateVerification.builder()
                .templateId(templateId)
                .projectId(projectId)
                .verifiedAt(Instant.now())
                .build());
    }
}
