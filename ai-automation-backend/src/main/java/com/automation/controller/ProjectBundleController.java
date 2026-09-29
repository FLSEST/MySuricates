package com.automation.controller;

import com.automation.dto.bundle.BundleImportResult;
import com.automation.dto.bundle.BundlePreview;
import com.automation.dto.bundle.ProjectBundle;
import com.automation.service.project.bundle.ProjectBundleExportService;
import com.automation.service.project.bundle.ProjectBundleImportService;
import com.automation.service.project.bundle.ProjectBundlePreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A whole project as one file: export, preview of an import, import. Core like the projects themselves;
 * chains inside a bundle follow module chains in the services (skipped with a warning when it is off).
 * Preview and import read the raw body so the 5 MB limit applies before parsing.
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectBundleController {

    private final ProjectBundleExportService exportService;
    private final ProjectBundlePreviewService previewService;
    private final ProjectBundleImportService importService;

    @GetMapping("/{id}/export")
    public ResponseEntity<ProjectBundle> exportProject(@PathVariable String id) {
        ProjectBundleExportService.Export export = exportService.export(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.fileName()).build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(export.bundle());
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BundlePreview previewImport(@RequestBody String body) {
        return previewService.preview(body);
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BundleImportResult> importProject(@RequestBody String body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(importService.importBundle(body));
    }
}
