package com.automation.service.project;

import com.automation.config.InstanceAddress;
import com.automation.dto.request.OnboardingConfigRequest;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.entity.Project;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.OnboardingProfileRepository;
import com.automation.service.workflow.ProjectWorkflowsChangedEvent;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TODO 644fd5ad: module sections, refreshing on changes, format switch, nothing while the module is off. */
class ProjectOnboardingStage2Test {

    @TempDir
    Path root;

    private final OnboardingProfileRepository profileRepository = mock(OnboardingProfileRepository.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final InstanceAddress address = new InstanceAddress("", "", 9090);

    private ProjectOnboardingService service(ModuleRegistry modules) {
        OnboardingProfile base = OnboardingProfile.builder().id("base-id").key("base").type(OnboardingProfileType.BASE)
                .content(Map.of("de", "Kern\n<!-- module: chains -->\nKetten-Abschnitt\n<!-- /module -->\nEnde\n"))
                .build();
        when(profileRepository.findByType(OnboardingProfileType.BASE)).thenReturn(List.of(base));
        return new ProjectOnboardingService(
                new OnboardingRenderer(profileRepository, workflowService, address, modules), modules, projectService);
    }

    private Project project(String id, Map<String, Object> onboarding) throws Exception {
        Path dir = Files.createDirectories(root.resolve(id));
        Project project = new Project();
        project.setId(id);
        project.setName(id);
        project.setPath(dir.toString());
        project.setConfig(new HashMap<>(onboarding == null ? Map.of() : Map.of("onboarding", new HashMap<>(onboarding))));
        return project;
    }

    private static Path rules(Project project) {
        return Path.of(project.getPath(), ".claude/rules/mysuricates.md");
    }

    @Test
    void aSectionOfADisabledModuleIsNotWritten() throws Exception {
        ProjectOnboardingService core = service(ModuleRegistry.of(Map.of("preset", "core"), Map.of()));
        Project p = project("p1", null);

        assertThat(core.preview(p)).contains("Kern").contains("Ende").doesNotContain("Ketten-Abschnitt").doesNotContain("module:");
        assertThat(service(ModuleRegistry.allEnabled()).preview(p)).contains("Ketten-Abschnitt").doesNotContain("module:");
    }

    @Test
    void switchingTheFormatRemovesTheOldFile_onlyIfItIsOurs() throws Exception {
        ProjectOnboardingService service = service(ModuleRegistry.allEnabled());
        Project p = project("p1", null);
        service.enable(p, new OnboardingConfigRequest("CLAUDE_RULES", List.of(), "de"));
        assertThat(rules(p)).exists();

        service.enable(p, new OnboardingConfigRequest("AGENTS_MD", List.of(), "de"));

        assertThat(rules(p)).doesNotExist();
        assertThat(Path.of(p.getPath(), "AGENTS.md")).exists();
    }

    @Test
    void aChangeOfAProjectsWorkflowsRewritesOnlyThatProjectsFile() throws Exception {
        ProjectOnboardingService service = service(ModuleRegistry.allEnabled());
        Project mine = project("mine", Map.of("enabled", true, "format", "CLAUDE_RULES", "language", "de"));
        Project other = project("other", Map.of("enabled", true, "format", "CLAUDE_RULES", "language", "de"));
        when(projectService.findAll()).thenReturn(List.of(mine, other));

        service.onProjectWorkflowsChanged(new ProjectWorkflowsChangedEvent("mine"));

        assertThat(rules(mine)).exists();
        assertThat(rules(other)).doesNotExist();
    }

    @Test
    void aChangedProfileRewritesTheProjectsThatIncludeIt_theBaseOneEveryProject() throws Exception {
        ProjectOnboardingService service = service(ModuleRegistry.allEnabled());
        Project with = project("with", Map.of("enabled", true, "profileIds", List.of("dev"), "language", "de"));
        Project without = project("without", Map.of("enabled", true, "profileIds", List.of(), "language", "de"));
        Project off = project("off", Map.of("enabled", false));
        when(projectService.findAll()).thenReturn(List.of(with, without, off));

        service.onProfileChanged(new OnboardingProfileChangedEvent("dev", false));
        assertThat(rules(with)).exists();
        assertThat(rules(without)).doesNotExist();

        service.onProfileChanged(new OnboardingProfileChangedEvent("base-id", true));
        assertThat(rules(without)).exists();
        assertThat(rules(off)).doesNotExist();
    }

    @Test
    void nothingIsRewrittenWhileTheModuleIsOff() throws Exception {
        ProjectOnboardingService core = service(ModuleRegistry.of(Map.of("preset", "core"), Map.of()));
        Project p = project("p1", Map.of("enabled", true, "language", "de"));
        when(projectService.findAll()).thenReturn(List.of(p));

        assertThat(core.refreshEnabled(project -> true)).isZero();
        assertThat(rules(p)).doesNotExist();
    }
}
