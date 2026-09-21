package com.automation.service.example;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.service.project.ProjectService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.automation.util.LocalizedJson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The example project a fresh installation creates with one click (TODO d12c7560): a copy of the
 * bundled {@code classpath:examples/example-project/} plus one workflow per built-in {@code EXAMPLE}
 * template.
 *
 * <p>Idempotent: an active project that already points at the copy is returned instead of a second
 * one, missing example workflows are added, and an existing copy is never overwritten — whoever wants
 * a fresh start deletes the directory.
 */
@Service
@Slf4j
public class ExampleProjectService {

    static final String SOURCE_ROOT = "examples/example-project/";
    static final String DIRECTORY_NAME = "example-project";
    static final String TEMPLATE_CATEGORY = "EXAMPLE";

    private static final Map<String, String> NAME = Map.of(
            "de", "Beispielprojekt",
            "en", "Example project");
    private static final Map<String, String> SUBTITLE = Map.of(
            "de", "Mitgelieferte Beispiele zum Ausprobieren",
            "en", "Bundled examples to try out");
    private static final Map<String, String> DESCRIPTION = Map.of(
            "de", "Die Beispiel-Workflows kommen ohne KI aus und schreiben nichts außerhalb ihres Laufs. "
                    + "Das Verzeichnis ist eine Kopie der mitgelieferten Vorlage, Änderungen daran sind erlaubt.",
            "en", "The example workflows need no AI and write nothing outside their run. "
                    + "The directory is a copy of the bundled template, so feel free to change it.");

    public record Result(Project project, boolean created, int workflowsAdded) {}

    private final ProjectService projectService;
    private final WorkflowService workflowService;
    private final WorkflowTemplateService templateService;
    private final TransactionTemplate transactionTemplate;
    private final Path baseDirectory;

    public ExampleProjectService(ProjectService projectService,
                                 WorkflowService workflowService,
                                 WorkflowTemplateService templateService,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${automation.examples.directory:}") String directory,
                                 @Value("${automation.workspace.root:}") String workspaceRoot) {
        this.projectService = projectService;
        this.workflowService = workflowService;
        this.templateService = templateService;
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
        copyBundledFiles(target);

        return transactionTemplate.execute(status -> {
            Optional<Project> existing = findActive(target);
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
        int added = 0;
        for (WorkflowTemplate template : templateService.findAll()) {
            if (!template.isBuiltIn() || !TEMPLATE_CATEGORY.equals(template.getCategory())
                    || present.contains(template.getId())) {
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

    /**
     * Copies the bundled files into {@code target} unless it exists. The copy is assembled in a
     * sibling directory and moved into place, so a failed copy never leaves a half-filled target
     * that the existence check would then keep forever.
     */
    void copyBundledFiles(Path target) {
        if (Files.exists(target)) {
            return;
        }
        Path staging = null;
        try {
            Files.createDirectories(target.getParent());
            staging = Files.createTempDirectory(target.getParent(), "." + DIRECTORY_NAME + "-");
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:" + SOURCE_ROOT + "**/*");
            for (Resource resource : resources) {
                copyResource(resource, staging);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
            log.info("Copied the bundled example project to {}", target);
        } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
            log.debug("Example project directory appeared concurrently: {}", target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not copy the example project to " + target, e);
        } finally {
            deleteQuietly(staging);
        }
    }

    private static void copyResource(Resource resource, Path staging) throws IOException {
        String url = resource.getURL().toString();
        int start = url.lastIndexOf(SOURCE_ROOT);
        if (start < 0 || url.endsWith("/") || !resource.isReadable()) {
            return;   // directory entry (jar) or directory (file system)
        }
        String relative = url.substring(start + SOURCE_ROOT.length());
        Path file = staging.resolve(relative).normalize();
        if (relative.isEmpty() || !file.startsWith(staging)) {
            return;
        }
        Files.createDirectories(file.getParent());
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, file);
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Could not remove the staging directory {}", directory, e);
        }
    }
}
