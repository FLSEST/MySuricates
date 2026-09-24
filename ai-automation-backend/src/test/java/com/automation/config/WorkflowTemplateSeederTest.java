package com.automation.config;

import com.automation.entity.BlockDefinition;
import com.automation.entity.WorkflowTemplate;
import com.automation.repository.WorkflowTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowTemplateSeederTest {

    @Mock
    private WorkflowTemplateRepository templateRepository;

    @Mock
    private BuiltInTemplateLoader templateLoader;

    private WorkflowTemplateSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new WorkflowTemplateSeeder(templateRepository, templateLoader);
    }

    private WorkflowTemplate template(String id, String enName, String version) {
        return WorkflowTemplate.builder()
                .id(id)
                .name(Map.of("en", enName, "de", enName))
                .description(Map.of("en", "desc", "de", "desc"))
                .icon("\uD83D\uDE80")
                .category("CODE")
                .sortOrder(0)
                .version(version)
                .blocks(List.of(BlockDefinition.builder().type("shell")
                        .config(Map.of("command", "echo test")).build()))
                .builtIn(true)
                .build();
    }

    @Test
    void run_emptyDb_seedsAllTemplates() throws Exception {
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of());
        when(templateLoader.loadAll()).thenReturn(List.of(
                template("t1", "Template 1", "1.0.0"),
                template("t2", "Template 2", "1.0.0")
        ));

        seeder.run(null);

        verify(templateRepository, times(2)).save(any(WorkflowTemplate.class));
    }

    @Test
    void run_allExist_skipsSeeding() throws Exception {
        WorkflowTemplate existing = template("t1", "Template 1", "1.0.0");
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of(existing));
        when(templateLoader.loadAll()).thenReturn(List.of(
                template("t1", "Template 1", "1.0.0")
        ));

        seeder.run(null);

        verify(templateRepository, never()).save(any());
    }

    @Test
    void run_partialState_seedsOnlyMissing() throws Exception {
        WorkflowTemplate existing = template("t1", "Template 1", "1.0.0");
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of(existing));
        when(templateLoader.loadAll()).thenReturn(List.of(
                template("t1", "Template 1", "1.0.0"),
                template("t2", "Template 2", "1.0.0")
        ));

        seeder.run(null);

        ArgumentCaptor<WorkflowTemplate> captor = ArgumentCaptor.forClass(WorkflowTemplate.class);
        verify(templateRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("t2");
    }

    @Test
    void run_newerVersion_updatesExisting() throws Exception {
        WorkflowTemplate existing = template("t1", "Template 1", "1.0.0");
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of(existing));

        WorkflowTemplate newer = template("t1", "Template 1", "1.1.0");
        newer.setInstructions("Updated instructions");
        when(templateLoader.loadAll()).thenReturn(List.of(newer));

        seeder.run(null);

        // The existing entity should be updated and saved
        verify(templateRepository, times(1)).save(existing);
        assertThat(existing.getVersion()).isEqualTo("1.1.0");
    }

    @Test
    void run_newerVersion_copiesTheBilingualSource() throws Exception {
        WorkflowTemplate existing = template("t1", "Template 1", "1.0.0");
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of(existing));
        WorkflowTemplate newer = template("t1", "Template 1", "1.1.0");
        newer.setI18nSource("{\"i18n\":[\"de\",\"en\"]}");
        when(templateLoader.loadAll()).thenReturn(List.of(newer));

        seeder.run(null);

        assertThat(existing.getI18nSource()).isEqualTo("{\"i18n\":[\"de\",\"en\"]}");
    }

    @Test
    void run_olderVersion_doesNotDowngrade() throws Exception {
        WorkflowTemplate existing = template("t1", "Template 1", "2.0.0");
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of(existing));
        when(templateLoader.loadAll()).thenReturn(List.of(
                template("t1", "Template 1", "1.0.0")
        ));

        seeder.run(null);

        verify(templateRepository, never()).save(any());
    }

    // --- Built-ins that no longer ship (TODO 7ddacd1d) ---

    @Test
    void run_removesBuiltInsThatNoLongerShip() throws Exception {
        WorkflowTemplate kept = template("t1", "Template 1", "1.0.0");
        WorkflowTemplate moved = template("saas-scaffold", "SaaS — Scaffold App", "1.0.0");
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of(kept, moved));
        when(templateLoader.shippedIds()).thenReturn(Set.of("t1"));
        when(templateLoader.loadAll()).thenReturn(List.of(template("t1", "Template 1", "1.0.0")));

        seeder.run(null);

        verify(templateRepository).deleteAll(List.of(moved));
        verify(templateRepository, never()).save(any());
    }

    @Test
    void run_insertsTheShippedTemplateThatALeftOverWithTheSameNameHeldBack() throws Exception {
        // Rows from before the ids were stable carry a random id and the English name of a shipped template
        WorkflowTemplate leftOver = template("55c39868-9613-4b05-ae23-5930bc6af862", "Code Quality", "1.0.0");
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of(leftOver));
        when(templateLoader.shippedIds()).thenReturn(Set.of("code-quality"));
        WorkflowTemplate shipped = template("code-quality", "Code Quality", "1.0.1");
        when(templateLoader.loadAll()).thenReturn(List.of(shipped));

        seeder.run(null);

        verify(templateRepository).deleteAll(List.of(leftOver));
        verify(templateRepository).save(shipped);
    }

    @Test
    void run_removesNothingWhenNoTemplateFileIsFound() throws Exception {
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of(template("t1", "Template 1", "1.0.0")));
        when(templateLoader.shippedIds()).thenReturn(Set.of());
        when(templateLoader.loadAll()).thenReturn(List.of());

        seeder.run(null);

        verify(templateRepository, never()).deleteAll(anyIterable());
        verify(templateRepository, never()).delete(any());
    }

    @Test
    void run_removesNothingWhileEveryBuiltInStillShips() throws Exception {
        when(templateRepository.findByBuiltInTrue()).thenReturn(List.of(template("t1", "Template 1", "1.0.0")));
        when(templateLoader.shippedIds()).thenReturn(Set.of("t1", "t2"));
        when(templateLoader.loadAll()).thenReturn(List.of(template("t1", "Template 1", "1.0.0")));

        seeder.run(null);

        verify(templateRepository, never()).deleteAll(anyIterable());
    }

    @Test
    void isNewerVersion_comparesCorrectly() {
        assertThat(WorkflowTemplateSeeder.isNewerVersion("1.1.0", "1.0.0")).isTrue();
        assertThat(WorkflowTemplateSeeder.isNewerVersion("2.0.0", "1.9.9")).isTrue();
        assertThat(WorkflowTemplateSeeder.isNewerVersion("1.0.1", "1.0.0")).isTrue();
        assertThat(WorkflowTemplateSeeder.isNewerVersion("1.0.0", "1.0.0")).isFalse();
        assertThat(WorkflowTemplateSeeder.isNewerVersion("1.0.0", "1.1.0")).isFalse();
        assertThat(WorkflowTemplateSeeder.isNewerVersion("1.0.0", null)).isTrue();
        assertThat(WorkflowTemplateSeeder.isNewerVersion(null, "1.0.0")).isFalse();
    }
}
