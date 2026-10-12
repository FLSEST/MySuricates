package com.automation.service.project;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.UpdateProjectRequest;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ContainerService containerService;
    private final PathValidator pathValidator;
    private final ActivityLogService activityLogService;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;

    public ProjectService(ProjectRepository projectRepository,
                          ContainerService containerService,
                          PathValidator pathValidator,
                          ActivityLogService activityLogService,
                          PlatformTransactionManager transactionManager) {
        this.projectRepository = projectRepository;
        this.containerService = containerService;
        this.pathValidator = pathValidator;
        this.activityLogService = activityLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    /**
     * The list for {@code GET /api/v1/projects} - with the container fetched along.
     *
     * <p>Deliberately not {@code findByArchivedAtIsNull()}: the nullable
     * {@code @OneToOne(mappedBy = "project")} would otherwise cost one query of its own per project
     * (TODO 6d0ab09f).
     */
    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return projectRepository.findActiveWithContainer();
    }

    @Transactional(readOnly = true)
    public Project findById(String id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", id));
        if (project.getArchivedAt() != null) {
            throw new ResourceNotFoundException("Project", id);
        }
        return project;
    }

    @Transactional
    public Project create(CreateProjectRequest request) {
        validatePath(request.path());
        return save(request);
    }

    /**
     * Like {@link #create}, but the folder may still be unknown — a project imported from a bundle.
     * An empty path is stored as {@code ""}; any other path is validated as usual.
     */
    @Transactional
    public Project createWithOptionalPath(CreateProjectRequest request) {
        if (request.path() == null || request.path().isBlank()) {
            return save(new CreateProjectRequest(request.name(), request.subtitle(), request.description(),
                    request.type(), "", request.config(), request.aiContext()));
        }
        return create(request);
    }

    private Project save(CreateProjectRequest request) {
        Project project = Project.builder()
                .name(request.name())
                .subtitle(request.subtitle())
                .description(request.description())
                .type(request.type())
                .path(request.path())
                .config(request.config() != null ? request.config() : new java.util.HashMap<>())
                .aiContext(request.aiContext())
                .status(ProjectStatus.STOPPED)
                .build();

        Project saved = projectRepository.save(project);
        log.info("Created project: {} ({})", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Project created: " + saved.getName(), "ProjectService", saved.getId());
        return saved;
    }

    @Transactional
    public Project update(String id, UpdateProjectRequest request) {
        Project project = findById(id);

        if (request.name() != null) {
            // Create requires @NotBlank; the partial update must not let an empty name through either.
            if (request.name().isBlank()) {
                throw new LocalizedArgumentException(ErrorMessage.PROJECT_NAME_REQUIRED);
            }
            project.setName(request.name());
        }
        if (request.subtitle() != null) {
            project.setSubtitle(request.subtitle());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        if (request.type() != null) {
            project.setType(request.type());
        }
        if (request.path() != null) {
            validatePath(request.path());
            project.setPath(request.path());
        }
        if (request.config() != null) {
            project.setConfig(request.config());
        }
        if (request.aiContext() != null) {
            project.setAiContext(request.aiContext().isBlank() ? null : request.aiContext());
        }

        Project saved = projectRepository.save(project);
        log.info("Updated project: {} ({})", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Project updated: " + saved.getName(), "ProjectService", saved.getId());
        return saved;
    }

    @Transactional
    public void updateConfig(String id, Map<String, Object> config) {
        Project project = findById(id);
        project.setConfig(config);
        projectRepository.save(project);
    }

    /**
     * Archives the project and stops its container afterwards. Deliberately not
     * {@code @Transactional}: {@link ContainerService#stopContainer} does a multi-second Docker
     * stop+remove, so an enclosing transaction would pin the single pooled connection for that
     * whole time — the same lock-order inversion between per-project start and connection that
     * {@link #startContainer} avoids (TODO b3c88eeb / 0db2b2c9).
     *
     * <p>The order is a deliberate trade-off: the archive burst commits <em>first</em>, the Docker
     * stop runs outside any transaction afterwards. If Docker fails, the project stays archived
     * and only a stale container is left behind — {@code DockerOrphanReconciler} and the idle
     * cleanup reap it. A project the user already deleted popping back up un-archived would be
     * the worse outcome.
     */
    public void delete(String id) {
        record Archived(Project project, boolean wasRunning) {}
        Archived archived = transactionTemplate.execute(status -> {
            Project p = projectRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Project", id));
            // A workflow run starts the container itself (RunContainerPreparation) without setting
            // the project RUNNING; its RUNNING container row also keeps DockerOrphanReconciler away.
            boolean wasRunning = p.getStatus() == ProjectStatus.RUNNING
                    || (p.getContainer() != null && p.getContainer().getStatus() == ContainerStatus.RUNNING);
            p.setArchivedAt(Instant.now());
            // An archived project has no container after this call (stopped below, or reaped by the
            // reconciler if Docker fails); restore() must not bring it back as RUNNING without one.
            p.setStatus(ProjectStatus.STOPPED);
            projectRepository.save(p);
            log.info("Archived project: {} ({})", p.getName(), p.getId());
            activityLogService.warn(LogCategory.SYSTEM, "Project archived: " + p.getName(), "ProjectService", p.getId());
            return new Archived(p, wasRunning);
        });

        if (archived.wasRunning()) {
            Project project = archived.project();
            try {
                containerService.stopContainer(project);
            } catch (Exception e) {
                log.error("Failed to stop container for archived project: {}", id, e);
                activityLogService.error(LogCategory.CONTAINER,
                        "Failed to stop container for archived project: " + id, "ProjectService", id);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Project> findArchived() {
        return projectRepository.findByArchivedAtIsNotNull();
    }

    @Transactional
    public Project restore(String id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", id));
        project.setArchivedAt(null);
        Project saved = projectRepository.save(project);
        log.info("Restored project: {} ({})", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Project restored: " + saved.getName(), "ProjectService", saved.getId());
        return saved;
    }

    /**
     * Deliberately not {@code @Transactional}: {@link ContainerService#createAndStartContainer}
     * runs a 5–15 s Docker create as the single start in flight for the project (or waits for the
     * one already running). With HikariCP pool-size 1 an enclosing transaction would pin the single
     * connection for that whole time, so a workflow run that is already starting the container
     * blocks on the pool until connection-timeout (120 s) — a lock-order inversion between the
     * per-project start and the connection (TODO b3c88eeb). Same shape as ContainerService:
     * short write burst (STARTING) → Docker outside any transaction → short write burst
     * (RUNNING/ERROR).
     */
    public Project startContainer(String id) {
        Project project = transactionTemplate.execute(status -> {
            Project p = findById(id);
            p.setStatus(ProjectStatus.STARTING);
            return projectRepository.save(p);
        });

        ProjectStatus outcome = ProjectStatus.RUNNING;
        try {
            containerService.createAndStartContainer(project);
        } catch (Exception e) {
            log.error("Failed to start container for project: {}", id, e);
            outcome = ProjectStatus.ERROR;
        }

        return saveStatus(id, outcome);
    }

    /** Not {@code @Transactional} either: the Docker stop+remove is multi-second — see {@link #startContainer}. */
    public Project stopContainer(String id) {
        Project project = readOnlyTransactionTemplate.execute(status -> findById(id));
        containerService.stopContainer(project);
        return saveStatus(id, ProjectStatus.STOPPED);
    }

    /**
     * Write burst after the Docker call: reloads the project by id (the instance handed to
     * ContainerService is detached, and the eagerly mapped {@code container} association is
     * picked up by the fresh load) and persists the new status.
     */
    private Project saveStatus(String id, ProjectStatus status) {
        return transactionTemplate.execute(txStatus -> {
            Project p = findById(id);
            p.setStatus(status);
            return projectRepository.save(p);
        });
    }

    @Transactional
    public Project getOrCreateCollectionProject() {
        return projectRepository.findFirstByName("Sammlung")
                .orElseGet(() -> {
                    Project project = Project.builder()
                            .name("Sammlung")
                            .subtitle("Importierte Workflows")
                            .type(ProjectType.PERSONAL)
                            .path("/tmp")
                            .status(ProjectStatus.STOPPED)
                            .build();
                    return projectRepository.save(project);
                });
    }

    private void validatePath(String path) {
        pathValidator.validate(path);
    }
}
