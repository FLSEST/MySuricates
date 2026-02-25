package com.automation.service.todo;

import com.automation.entity.Project;
import com.automation.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProjectPathResolver {

    private final ProjectRepository projectRepository;

    /**
     * Finds the project whose path is the longest prefix of the given file path.
     */
    public Optional<Project> resolve(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }

        return projectRepository.findAll().stream()
                .filter(p -> p.getPath() != null && filePath.startsWith(p.getPath()))
                .max(Comparator.comparingInt(p -> p.getPath().length()));
    }

    /**
     * Resolves projects for multiple file paths, returning distinct matches.
     */
    public List<Project> resolveAll(List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return List.of();
        }

        List<Project> allProjects = projectRepository.findAll();

        return filePaths.stream()
                .filter(fp -> fp != null && !fp.isBlank())
                .map(fp -> allProjects.stream()
                        .filter(p -> p.getPath() != null && fp.startsWith(p.getPath()))
                        .max(Comparator.comparingInt(p -> p.getPath().length()))
                        .orElse(null))
                .filter(p -> p != null)
                .distinct()
                .toList();
    }
}
