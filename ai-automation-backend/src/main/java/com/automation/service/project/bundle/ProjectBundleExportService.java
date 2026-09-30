package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.entity.Project;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.project.ProjectService;
import com.automation.service.system.SystemIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code GET /api/v1/projects/{id}/export}: loads a project with its workflows, chains and schedules
 * (a handful of queries, no per-workflow lookups) and lets {@link BundleAssembler} build the bundle.
 * Read-only; nothing slow happens inside the transaction.
 */
@Service
public class ProjectBundleExportService {

    /** The bundle plus the file name for {@code Content-Disposition}. */
    public record Export(ProjectBundle bundle, String fileName) {}

    private final ProjectService projectService;
    private final WorkflowRepository workflowRepository;
    private final WorkflowChainRepository chainRepository;
    private final ScheduledWorkflowRepository scheduleRepository;
    private final SkillRepository skillRepository;
    private final ModuleRegistry modules;
    private final SystemIdentityService identity;
    private final BundleAssembler assembler;
    private final Clock clock;

    public ProjectBundleExportService(ProjectService projectService, WorkflowRepository workflowRepository,
                                      WorkflowChainRepository chainRepository,
                                      ScheduledWorkflowRepository scheduleRepository, SkillRepository skillRepository,
                                      ModuleRegistry modules, SystemIdentityService identity,
                                      ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.workflowRepository = workflowRepository;
        this.chainRepository = chainRepository;
        this.scheduleRepository = scheduleRepository;
        this.skillRepository = skillRepository;
        this.modules = modules;
        this.identity = identity;
        this.assembler = new BundleAssembler(objectMapper);
        this.clock = Clock.systemUTC();
    }

    /** @throws com.automation.exception.ResourceNotFoundException unknown or archived project (404) */
    @Transactional(readOnly = true)
    public Export export(String projectId) {
        Project project = projectService.findById(projectId);
        List<Workflow> workflows = workflowRepository.findByProjectId(project.getId());
        Set<String> workflowIds = workflows.stream().map(Workflow::getId).collect(Collectors.toSet());
        List<WorkflowChain> chains = chainRepository.findAll();
        List<ScheduledWorkflow> schedules = scheduleRepository.findAllWithTargets().stream()
                .filter(s -> s.getWorkflow() != null && workflowIds.contains(s.getWorkflow().getId())
                        || s.getChain() != null)
                .toList();
        Map<String, String> skillNames = skillRepository.findAll().stream()
                .collect(Collectors.toMap(Skill::getId, Skill::getName, (a, b) -> a));

        ProjectBundle bundle = assembler.assemble(new BundleAssembler.Source(project, workflows, chains, schedules,
                skillNames, modules.isEnabled(ModuleCatalog.CHAINS), identity.getVersion(), Instant.now(clock)));
        return new Export(bundle, fileName(project.getName()));
    }

    static String fileName(String projectName) {
        return BundleKeys.slug(projectName, "project") + ".mysuricates-bundle.json";
    }
}
