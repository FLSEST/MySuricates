package com.automation.service.project;

import com.automation.config.InstanceAddress;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.OnboardingProfileRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.util.LocalizedJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders the onboarding text MySuricates writes into a project (TODOs a805d0a7, 644fd5ad): the
 * profiles in the project's language, sections of disabled modules left out ({@link ModuleSections}),
 * address and key command of this instance filled in ({@link InstanceAddress}), and the project's
 * workflows. Where the text goes and when is {@link ProjectOnboardingService}'s business; the snippet
 * endpoint uses the same rendering.
 */
@Component
@RequiredArgsConstructor
public class OnboardingRenderer {

    private final OnboardingProfileRepository profileRepository;
    private final WorkflowRepository workflowRepository;
    private final InstanceAddress instanceAddress;
    private final ModuleRegistry modules;

    /**
     * The whole file: marker line, base profile, the selected profiles and the project's workflows,
     * in {@code lang}. Sections of disabled modules are left out, placeholders filled.
     */
    public String render(Project project, String lang, List<String> profileIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- ").append(ProjectOnboardingService.MARKER).append(" — do not edit -->\n");
        sb.append("# MySuricates Integration\n\n");

        // Layer 1: Base profile
        profileRepository.findByType(OnboardingProfileType.BASE).stream()
                .findFirst()
                .ifPresent(base -> sb.append(fill(base, lang, project.getId())).append("\n\n"));

        // Layer 2: Selected profiles
        if (!profileIds.isEmpty()) {
            for (OnboardingProfile profile : profileRepository.findAllById(profileIds)) {
                sb.append(fill(profile, lang, project.getId())).append("\n\n");
            }
        }

        // Layer 3: Project workflows
        sb.append(renderWorkflowList(project, lang));

        return sb.toString().stripTrailing() + "\n";
    }

    /**
     * A profile's text in {@code lang} (falling back to the other language) with the placeholders
     * filled: project id, and address and key command of this instance as the installer set them.
     */
    public String fill(OnboardingProfile profile, String lang, String projectId) {
        String text = ModuleSections.apply(LocalizedJson.pick(profile.getContent(), lang), modules::isEnabled);
        return instanceAddress.fill(text == null ? "" : text).replace("{{PROJECT_ID}}", projectId);
    }

    /** The table of the project's workflows plus how to start one, in {@code lang}. */
    public String renderWorkflowList(Project project, String lang) {
        boolean de = "de".equals(LocalizedJson.normalize(lang));
        List<Workflow> workflows = workflowRepository.findByProjectId(project.getId());
        String heading = de ? "## Projekt-Workflows\n\n" : "## Project workflows\n\n";
        if (workflows.isEmpty()) {
            return heading + (de ? "_Noch keine Workflows in diesem Projekt._\n" : "_No workflows in this project yet._\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(heading);
        sb.append(de ? "| Workflow | ID | Blöcke | Beschreibung |\n" : "| Workflow | ID | Blocks | Description |\n");
        sb.append("|----------|-------|--------|-------------|\n");

        for (Workflow wf : workflows) {
            String name = wf.getName() != null ? wf.getName() : "—";
            String desc = wf.getDescription() != null ? wf.getDescription().replace("|", "\\|") : "—";
            if (desc.length() > 80) {
                desc = desc.substring(0, 77) + "...";
            }
            int blockCount = wf.getBlocks() != null ? wf.getBlocks().size() : 0;
            sb.append("| ").append(name)
                    .append(" | `").append(wf.getId()).append("`")
                    .append(" | ").append(blockCount)
                    .append(" | ").append(desc)
                    .append(" |\n");
        }

        sb.append(de ? "\n### Workflow ausführen\n\n" : "\n### Run a workflow\n\n");
        sb.append("```bash\n");
        sb.append("curl -s -X POST ").append(instanceAddress.baseUrl()).append("/api/v1/workflows/<ID>/execute \\\n");
        sb.append("  -H \"Content-Type: application/json\" -H \"X-API-Key: $API_KEY\" \\\n");
        sb.append("  -d '{\"context\": {}}'\n");
        sb.append("```\n");

        return sb.toString();
    }
}
