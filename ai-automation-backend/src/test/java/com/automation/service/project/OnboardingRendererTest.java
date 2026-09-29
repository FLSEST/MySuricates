package com.automation.service.project;

import com.automation.config.InstanceAddress;
import com.automation.entity.BlockDefinition;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.OnboardingProfileRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Der Onboarding-Text: Schichten, Sprache, Platzhalter, Modulabschnitte und die Workflow-Tabelle. */
@ExtendWith(MockitoExtension.class)
class OnboardingRendererTest {

    private static final InstanceAddress ADDRESS = new InstanceAddress("http://localhost:9095/", "", 9090);

    @Mock private OnboardingProfileRepository profileRepository;
    @Mock private WorkflowRepository workflowRepository;

    private OnboardingRenderer renderer() {
        return renderer(ModuleRegistry.allEnabled());
    }

    private OnboardingRenderer renderer(ModuleRegistry modules) {
        lenient().when(workflowRepository.findByProjectId("p-1")).thenReturn(List.of());
        return new OnboardingRenderer(profileRepository, workflowRepository, ADDRESS, modules);
    }

    @Test
    void render_stacksMarkerBaseSelectedProfilesAndWorkflowList() {
        when(profileRepository.findByType(OnboardingProfileType.BASE)).thenReturn(List.of(
                profile(Map.of("en", "BASE {{BASE_URL}} {{PROJECT_ID}}"))));
        when(profileRepository.findAllById(List.of("x"))).thenReturn(List.of(profile(Map.of("en", "EXTRA"))));

        String text = renderer().render(project(), "en", List.of("x"));

        assertThat(text).startsWith("<!-- " + ProjectOnboardingService.MARKER + " — do not edit -->\n# MySuricates Integration\n\n");
        assertThat(text.indexOf("BASE http://localhost:9095 p-1")).isPositive()
                .isLessThan(text.indexOf("EXTRA"));
        assertThat(text.indexOf("EXTRA")).isLessThan(text.indexOf("## Project workflows"));
        assertThat(text).endsWith("_No workflows in this project yet._\n").doesNotEndWith("\n\n");
    }

    @Test
    void render_withoutBaseProfileOrSelection_skipsThoseLayers() {
        when(profileRepository.findByType(OnboardingProfileType.BASE)).thenReturn(List.of());

        String text = renderer().render(project(), "de", List.of());

        assertThat(text).contains("# MySuricates Integration\n\n## Projekt-Workflows")
                .contains("_Noch keine Workflows in diesem Projekt._");
        verify(profileRepository).findByType(OnboardingProfileType.BASE);
        verifyNoMoreInteractions(profileRepository);
    }

    @Test
    void fill_picksLanguageWithFallback_andFillsAllPlaceholders() {
        OnboardingProfile profile = profile(Map.of("en", "url={{BASE_URL}} key={{API_KEY_COMMAND}} id={{PROJECT_ID}}"));

        String filled = renderer().fill(profile, "de", "p-1");

        assertThat(filled).startsWith("url=http://localhost:9095 key=cat '").contains("id=p-1")
                .doesNotContain("{{");
        assertThat(renderer().fill(profile(Map.of("de", "nur deutsch")), "en", "p-1")).isEqualTo("nur deutsch");
    }

    @Test
    void fill_emptyContentGivesEmptyText() {
        assertThat(renderer().fill(profile(Map.of()), "en", "p-1")).isEmpty();
    }

    @Test
    void fill_dropsSectionsOfDisabledModules() {
        OnboardingProfile profile = profile(Map.of("en",
                "vorher\n<!-- module: chains -->\nKetten\n<!-- /module -->\nnachher"));

        String core = renderer(ModuleRegistry.of(Map.of("preset", "core"), Map.of())).fill(profile, "en", "p-1");
        String full = renderer().fill(profile, "en", "p-1");

        assertThat(core).contains("vorher").contains("nachher").doesNotContain("Ketten").doesNotContain("module:");
        assertThat(full).contains("Ketten").doesNotContain("module:");
    }

    @Test
    void workflowList_rendersTableRowsWithCountsEscapingAndTruncation() {
        Workflow full = Workflow.builder().id("w-1").name("Build")
                .description("a|b " + "x".repeat(100))
                .blocks(List.of(BlockDefinition.builder().type("shell").build(),
                        BlockDefinition.builder().type("simple").build())).build();
        Workflow bare = Workflow.builder().id("w-2").name(null).description(null).blocks(null).build();
        when(workflowRepository.findByProjectId("p-1")).thenReturn(List.of(full, bare));

        String text = new OnboardingRenderer(profileRepository, workflowRepository, ADDRESS,
                ModuleRegistry.allEnabled()).renderWorkflowList(project(), "en");

        assertThat(text).startsWith("## Project workflows\n\n| Workflow | ID | Blocks | Description |\n");
        assertThat(text).contains("| Build | `w-1` | 2 | a\\|b " + "x".repeat(72) + "... |\n");
        assertThat(text).contains("| — | `w-2` | 0 | — |\n");
        assertThat(text).contains("### Run a workflow")
                .contains("curl -s -X POST http://localhost:9095/api/v1/workflows/<ID>/execute");
    }

    @Test
    void workflowList_germanHeadings() {
        Workflow wf = Workflow.builder().id("w-1").name("Build").build();
        when(workflowRepository.findByProjectId("p-1")).thenReturn(List.of(wf));

        String text = new OnboardingRenderer(profileRepository, workflowRepository, ADDRESS,
                ModuleRegistry.allEnabled()).renderWorkflowList(project(), "de-DE");

        assertThat(text).contains("## Projekt-Workflows").contains("| Workflow | ID | Blöcke | Beschreibung |")
                .contains("### Workflow ausführen");
    }

    private static Project project() {
        return Project.builder().id("p-1").name("Demo").build();
    }

    private static OnboardingProfile profile(Map<String, String> content) {
        return OnboardingProfile.builder().key("k").content(content).type(OnboardingProfileType.PROFILE).build();
    }
}
