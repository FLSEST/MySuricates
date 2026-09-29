package com.automation.service.project.bundle;

import com.automation.dto.bundle.BundlePreview;
import com.automation.dto.bundle.BundlePreviewRequest;
import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleDefinition;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code POST /api/v1/projects/import/preview}: validates a bundle and shows what an import would
 * create — block types, providers, the shell commands it brings along, name conflicts, warnings.
 * Writes nothing.
 */
@Service
public class ProjectBundlePreviewService {

    private final BundleRequestReader reader;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final WorkflowRepository workflowRepository;
    private final SkillRepository skillRepository;
    private final ModuleRegistry modules;

    public ProjectBundlePreviewService(BundleRequestReader reader, ProjectRepository projectRepository,
                                       ProjectService projectService, WorkflowRepository workflowRepository,
                                       SkillRepository skillRepository, ModuleRegistry modules) {
        this.reader = reader;
        this.projectRepository = projectRepository;
        this.projectService = projectService;
        this.workflowRepository = workflowRepository;
        this.skillRepository = skillRepository;
        this.modules = modules;
    }

    @Transactional(readOnly = true)
    public BundlePreview preview(String body) {
        BundlePreviewRequest request = reader.read(body, BundlePreviewRequest.class);
        ProjectBundle bundle = request.bundle();
        BundleValidator.validate(bundle);

        String projectName = bundle.project().name();
        boolean nameTaken = projectRepository.findByArchivedAtIsNull().stream()
                .map(Project::getName).anyMatch(projectName::equals);
        Set<String> targetNames = Set.of();
        if (request.targetProjectId() != null && !request.targetProjectId().isBlank()) {
            Project target = projectService.findById(request.targetProjectId());
            targetNames = workflowRepository.findByProjectId(target.getId()).stream()
                    .map(Workflow::getName).collect(Collectors.toSet());
        }

        List<BundlePreview.WorkflowInfo> workflows = new ArrayList<>();
        for (BundleWorkflow w : bundle.workflows()) {
            workflows.add(workflowInfo(w, targetNames));
        }
        List<BundlePreview.ChainInfo> chains = bundle.chains().stream()
                .map(c -> new BundlePreview.ChainInfo(c.key(), c.name(), c.entries().size())).toList();
        List<BundlePreview.ScheduleInfo> schedules = bundle.schedules().stream()
                .map(s -> new BundlePreview.ScheduleInfo(s.target().label(), s.cronExpression(), true)).toList();

        return new BundlePreview(new BundlePreview.ProjectInfo(projectName, nameTaken), workflows, chains,
                schedules, bundle.paths(), warnings(bundle));
    }

    private static BundlePreview.WorkflowInfo workflowInfo(BundleWorkflow w, Set<String> targetNames) {
        BundleDefinition d = w.definition();
        Set<String> providers = new LinkedHashSet<>();
        addIfPresent(providers, d.provider());
        providers.addAll(BundleBlocks.configValues(d.blocks(), "provider"));
        Set<String> models = new LinkedHashSet<>();
        addIfPresent(models, d.model());
        models.addAll(BundleBlocks.configValues(d.blocks(), "model"));
        return new BundlePreview.WorkflowInfo(w.key(), d.name(), d.blocks() == null ? 0 : d.blocks().size(),
                BundleBlocks.blockTypes(d.blocks()), List.copyOf(providers), List.copyOf(models),
                BundleBlocks.shellCommands(d.blocks()), targetNames.contains(d.name()));
    }

    /** The exporter's warnings plus what only this instance knows: missing skills, a switched-off module. */
    private List<String> warnings(ProjectBundle bundle) {
        Set<String> known = skillRepository.findAll().stream().map(Skill::getName).collect(Collectors.toSet());
        return BundleWarnings.of(bundle, known, modules.isEnabled(ModuleCatalog.CHAINS));
    }

    private static void addIfPresent(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value);
        }
    }
}
