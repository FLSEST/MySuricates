package com.automation.service.project;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.UpdateProjectRequest;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ContainerService containerService;
    private final PathValidator pathValidator;

    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return projectRepository.findByArchivedAtIsNull();
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
        return saved;
    }

    @Transactional
    public Project update(String id, UpdateProjectRequest request) {
        Project project = findById(id);

        if (request.name() != null) {
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
        return saved;
    }

    @Transactional
    public void delete(String id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", id));

        if (project.getStatus() == ProjectStatus.RUNNING) {
            containerService.stopContainer(project);
        }

        project.setArchivedAt(Instant.now());
        projectRepository.save(project);
        log.info("Archived project: {} ({})", project.getName(), project.getId());
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
        return saved;
    }

    @Transactional
    public Project startContainer(String id) {
        Project project = findById(id);
        project.setStatus(ProjectStatus.STARTING);
        projectRepository.save(project);

        try {
            containerService.createAndStartContainer(project);
            project.setStatus(ProjectStatus.RUNNING);
        } catch (Exception e) {
            log.error("Failed to start container for project: {}", id, e);
            project.setStatus(ProjectStatus.ERROR);
        }

        return projectRepository.save(project);
    }

    @Transactional
    public Project stopContainer(String id) {
        Project project = findById(id);
        containerService.stopContainer(project);
        project.setStatus(ProjectStatus.STOPPED);
        return projectRepository.save(project);
    }

    private void validatePath(String path) {
        pathValidator.validate(path);
    }
}
