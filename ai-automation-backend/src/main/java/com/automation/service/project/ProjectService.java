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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ContainerService containerService;

    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return projectRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Project findById(String id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", id));
    }

    @Transactional
    public Project create(CreateProjectRequest request) {
        validatePath(request.path());

        Project project = Project.builder()
                .name(request.name())
                .type(request.type())
                .path(request.path())
                .config(request.config() != null ? request.config() : new java.util.HashMap<>())
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

        Project saved = projectRepository.save(project);
        log.info("Updated project: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(String id) {
        Project project = findById(id);

        if (project.getStatus() == ProjectStatus.RUNNING) {
            containerService.stopContainer(project);
        }

        projectRepository.delete(project);
        log.info("Deleted project: {} ({})", project.getName(), project.getId());
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
        Path normalized = Path.of(path).normalize().toAbsolutePath();
        String normalizedStr = normalized.toString();

        // Block access to sensitive system directories
        if (normalizedStr.equals("/") || normalizedStr.startsWith("/etc")
                || normalizedStr.startsWith("/var/run") || normalizedStr.startsWith("/proc")
                || normalizedStr.startsWith("/sys") || normalizedStr.startsWith("/dev")) {
            throw new IllegalArgumentException("Path points to a restricted system directory: " + path);
        }

        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Path must be an existing directory: " + path);
        }
    }
}
