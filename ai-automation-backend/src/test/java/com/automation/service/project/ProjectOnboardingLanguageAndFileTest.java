package com.automation.service.project;

import com.automation.config.InstanceAddress;
import com.automation.dto.request.OnboardingConfigRequest;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.repository.OnboardingProfileRepository;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * What TODO a805d0a7 changed about the written file: language per project, the instance's own
 * address and key command, and a hand-written file is never overwritten.
 */
class ProjectOnboardingLanguageAndFileTest {

    @TempDir
    Path dir;

    private final OnboardingProfileRepository profileRepository = mock(OnboardingProfileRepository.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final InstanceAddress address = new InstanceAddress("http://localhost:9095", "mysuricates-main", 9090);
    private ProjectOnboardingService service;

    @BeforeEach
    void setUp() {
        service = new ProjectOnboardingService(profileRepository, workflowService, address, projectService);
        OnboardingProfile base = OnboardingProfile.builder().key("base").type(OnboardingProfileType.BASE)
                .content(Map.of(
                        "de", "Deutsch {{BASE_URL}} $({{API_KEY_COMMAND}}) {{PROJECT_ID}}",
                        "en", "English {{BASE_URL}} $({{API_KEY_COMMAND}}) {{PROJECT_ID}}"))
                .build();
        when(profileRepository.findByType(OnboardingProfileType.BASE)).thenReturn(List.of(base));
        Workflow workflow = Workflow.builder().id("wf-1").name("Tests").blocks(List.of()).build();
        when(workflowService.findByProjectId("p1")).thenReturn(List.of(workflow));
    }

    private Project project(Map<String, Object> onboarding) {
        Project project = new Project();
        project.setId("p1");
        project.setName("Demo");
        project.setPath(dir.toString());
        Map<String, Object> config = new HashMap<>();
        if (onboarding != null) {
            config.put("onboarding", onboarding);
        }
        project.setConfig(config);
        return project;
    }

    @Test
    void theChosenLanguageIsWritten_withTheInstancesAddressAndKeyCommand() throws Exception {
        service.enable(project(null), new OnboardingConfigRequest("CLAUDE_RULES", List.of(), "en"));

        String written = Files.readString(dir.resolve(".claude/rules/mysuricates.md"));
        assertThat(written).contains("English http://localhost:9095 $(docker exec mysuricates-main cat '")
                .contains(" p1")
                .contains("## Project workflows")
                .contains("curl -s -X POST http://localhost:9095/api/v1/workflows/<ID>/execute")
                .doesNotContain("Deutsch")
                .doesNotContain("{{BASE_URL}}").doesNotContain("{{API_KEY_COMMAND}}").doesNotContain("{{PROJECT_ID}}")
                .doesNotContain("localhost:9090");
    }

    @Test
    void projectsFromBeforeTheLanguageChoiceKeepGerman() {
        Project legacy = project(new HashMap<>(Map.of("enabled", true, "format", "CLAUDE_RULES")));

        assertThat(service.preview(legacy)).contains("Deutsch").contains("## Projekt-Workflows");
        assertThat(service.getStatus(legacy).language()).isEqualTo("de");
    }

    @Test
    void enablingWithoutALanguageKeepsTheProjectsCurrentOne() {
        Project english = project(new HashMap<>(Map.of("enabled", true, "language", "en")));

        service.enable(english, new OnboardingConfigRequest("CLAUDE_RULES", List.of(), null));

        @SuppressWarnings("unchecked")
        Map<String, Object> onboarding = (Map<String, Object>) english.getConfig().get("onboarding");
        assertThat(onboarding).containsEntry("language", "en");
    }

    @Test
    void aHandWrittenAgentsMdIsNeverOverwritten_andTheStatusSaysSo() throws Exception {
        Path agents = dir.resolve("AGENTS.md");
        Files.writeString(agents, "# Our own agent rules\n");

        service.enable(project(null), new OnboardingConfigRequest("AGENTS_MD", List.of(), "de"));

        assertThat(Files.readString(agents)).isEqualTo("# Our own agent rules\n");
        Project enabled = project(new HashMap<>(Map.of("enabled", true, "format", "AGENTS_MD", "language", "de")));
        var status = service.getStatus(enabled);
        assertThat(status.fileExists()).isTrue();
        assertThat(status.fileForeign()).isTrue();
    }

    @Test
    void aFileItWroteItselfIsReplaced() throws Exception {
        Path agents = dir.resolve("AGENTS.md");
        Files.writeString(agents, "<!-- " + ProjectOnboardingService.MARKER + " — do not edit -->\nold\n");

        service.enable(project(null), new OnboardingConfigRequest("AGENTS_MD", List.of(), "de"));

        assertThat(Files.readString(agents)).contains("Deutsch").doesNotContain("old");
        Project enabled = project(new HashMap<>(Map.of("enabled", true, "format", "AGENTS_MD", "language", "de")));
        assertThat(service.getStatus(enabled).fileForeign()).isFalse();
    }

    @Test
    void disablingLeavesAForeignFileAlone() throws Exception {
        Path agents = dir.resolve("AGENTS.md");
        Files.writeString(agents, "# Our own agent rules\n");

        service.disable(project(new HashMap<>(Map.of("enabled", true, "format", "AGENTS_MD"))));

        assertThat(agents).exists();
    }
}
