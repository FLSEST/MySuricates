package com.automation.service.example;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateProjectRequest;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.executor.BlockTypeModules;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.project.ProjectService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.automation.util.LocalizedJson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The example project a fresh installation creates with one click (TODO d12c7560): a copy of the
 * bundled {@code classpath:examples/example-project/} plus one workflow per shipped template listed in
 * {@link #EXAMPLE_TEMPLATE_IDS}.
 *
 * <p>Idempotent: an active project that already points at the copy is returned instead of a second
 * one, missing example workflows are added, and an existing copy is never overwritten — whoever wants
 * a fresh start deletes the directory. Two exceptions for an older copy (TODO c04ffb86): its
 * description and README are brought up to date while they are still the text an earlier version
 * shipped, word for word. An example whose blocks need a module that is switched off is left out
 * until the module is on again.
 */
@Service
@Slf4j
public class ExampleProjectService {

    static final String DIRECTORY_NAME = "example-project";
    /**
     * The shipped templates the example project gets, in this order. Named by id on purpose: the
     * first version picked them by the category {@code EXAMPLE}, and when its only template moved to
     * the blueprints (TODO 268ae0db) the example project silently came out empty (TODO 5162bd54).
     * {@code ExampleTemplatesShippedTest} checks the ids against the shipped files. One ability each,
     * in the blueprints' own order (TODO 2d782949): approval, branch, loop, TODOs, dashboard report.
     * Keep the list on one line — the claims fact sheet of the public-readiness chain counts it there.
     */
    static final List<String> EXAMPLE_TEMPLATE_IDS = List.of("blueprint-approval", "blueprint-if", "blueprint-loop", "blueprint-todo", "blueprint-report");

    private static final Map<String, String> NAME = Map.of(
            "de", "Beispielprojekt",
            "en", "Example project");
    private static final Map<String, String> SUBTITLE = Map.of(
            "de", "Mitgelieferte Beispiele zum Ausprobieren",
            "en", "Bundled examples to try out");
    private static final Map<String, String> DESCRIPTION = Map.of(
            "de", "Die Beispiel-Workflows kommen ohne KI aus. Außerhalb ihres Laufs bleibt nur das erledigte "
                    + "TODO des TODO-Beispiels zurück. "
                    + "Das Verzeichnis ist eine Kopie der mitgelieferten Vorlage, Änderungen daran sind erlaubt.",
            "en", "The example workflows need no AI. All they leave behind outside their run is the done TODO "
                    + "of the TODO example. "
                    + "The directory is a copy of the bundled template, so feel free to change it.");
    /** The descriptions earlier versions gave the project (e70307e8) — replaced while unchanged. */
    private static final Map<String, String> EARLIER_DESCRIPTION = Map.of(
            "de", "Die Beispiel-Workflows kommen ohne KI aus und schreiben nichts außerhalb ihres Laufs. "
                    + "Das Verzeichnis ist eine Kopie der mitgelieferten Vorlage, Änderungen daran sind erlaubt.",
            "en", "The example workflows need no AI and write nothing outside their run. "
                    + "The directory is a copy of the bundled template, so feel free to change it.");

    public record Result(Project project, boolean created, int workflowsAdded) {}

    private final ProjectService projectService;
    private final WorkflowService workflowService;
    private final WorkflowTemplateService templateService;
    private final ModuleRegistry modules;
    private final TransactionTemplate transactionTemplate;
    private final Path baseDirectory;

    public ExampleProjectService(ProjectService projectService,
                                 WorkflowService workflowService,
                                 WorkflowTemplateService templateService,
                                 ModuleRegistry modules,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${automation.examples.directory:}") String directory,
                                 @Value("${automation.workspace.root:}") String workspaceRoot) {
        this.projectService = projectService;
        this.workflowService = workflowService;
        this.templateService = templateService;
        this.modules = modules;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.baseDirectory = resolveBaseDirectory(directory, workspaceRoot);
    }

    /**
     * Where the copy goes: the explicit setting first; with a workspace root below it, because
     * {@code PathValidator} accepts nothing else there; otherwise {@code ./data/examples}, next to the
     * default database — on the container volume {@code /app/data} too.
     */
    static Path resolveBaseDirectory(String directory, String workspaceRoot) {
        if (directory != null && !directory.isBlank()) {
            return Path.of(directory).toAbsolutePath().normalize();
        }
        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            return Path.of(workspaceRoot, "mysuricates-examples").toAbsolutePath().normalize();
        }
        return Path.of("data", "examples").toAbsolutePath().normalize();
    }

    /**
     * Creates the example project, or returns the existing one and adds example workflows it lacks.
     * Deliberately not {@code @Transactional}: the file copy runs before the short DB burst.
     *
     * @param locale {@code de}/{@code en} — language of the project name and the example workflows
     */
    public Result ensure(String locale) {
        String lang = LocalizedJson.normalize(locale);
        Path target = baseDirectory.resolve(DIRECTORY_NAME);
        boolean existed = Files.exists(target);
        ExampleProjectFiles.copyBundled(target);
        if (existed) {
            ExampleProjectFiles.refreshUntouchedReadme(target);
        }

        return transactionTemplate.execute(status -> {
            Optional<Project> existing = findActive(target).map(this::refreshUntouchedDescription);
            Project project = existing.orElseGet(() -> projectService.create(new CreateProjectRequest(
                    LocalizedJson.pick(NAME, lang),
                    LocalizedJson.pick(SUBTITLE, lang),
                    LocalizedJson.pick(DESCRIPTION, lang),
                    ProjectType.PERSONAL,
                    target.toString(),
                    null,
                    null)));
            int added = addMissingExamples(project, lang);
            log.info("Example project {} ({}), {} example workflow(s) added",
                    existing.isPresent() ? "found" : "created", project.getId(), added);
            return new Result(project, existing.isEmpty(), added);
        });
    }

    private Optional<Project> findActive(Path target) {
        return projectService.findAll().stream()
                .filter(p -> p.getPath() != null && samePath(p.getPath(), target))
                .findFirst();
    }

    /** An older copy still carrying an earlier description gets the current one, in that language. */
    private Project refreshUntouchedDescription(Project project) {
        return EARLIER_DESCRIPTION.entrySet().stream()
                .filter(earlier -> earlier.getValue().equals(project.getDescription()))
                .findFirst()
                .map(earlier -> projectService.update(project.getId(), new UpdateProjectRequest(
                        null, null, DESCRIPTION.get(earlier.getKey()), null, null, null, null)))
                .orElse(project);
    }

    private static boolean samePath(String path, Path target) {
        try {
            return Path.of(path).toAbsolutePath().normalize().equals(target);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int addMissingExamples(Project project, String lang) {
        Set<String> present = workflowService.findByProjectId(project.getId()).stream()
                .map(Workflow::getTemplateId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, WorkflowTemplate> shipped = templateService.findAll().stream()
                .filter(WorkflowTemplate::isBuiltIn)
                .collect(Collectors.toMap(WorkflowTemplate::getId, t -> t, (a, b) -> a));
        int added = 0;
        for (String id : EXAMPLE_TEMPLATE_IDS) {
            WorkflowTemplate template = shipped.get(id);
            if (template == null) {
                log.warn("Example template {} is not installed; the example project stays without it", id);
                continue;
            }
            if (present.contains(id)) {
                continue;
            }
            List<ModuleCatalog> off = BlockTypeModules.requiredBy(template.getBlocks()).stream()
                    .filter(module -> !modules.isEnabled(module))
                    .toList();
            if (!off.isEmpty()) {
                log.info("Example template {} needs the switched-off module(s) {}; left out for now", id, off);
                continue;
            }
            workflowService.create(new CreateWorkflowRequest(
                    project.getId(),
                    LocalizedJson.pick(template.getName(), lang),
                    LocalizedJson.pick(template.getDescription(), lang),
                    template.getIcon(),
                    LocalizedJson.blocksFor(template.getI18nSource(), lang, template.getBlocks()),
                    List.of(),
                    LocalizedJson.instructionsFor(template.getI18nSource(), lang, template.getInstructions()),
                    template.getModel(),
                    null,
                    template.getId(),
                    null,
                    null,
                    null
            ), ChangeSource.EXAMPLE_PROJECT);
            added++;
        }
        return added;
    }
}
