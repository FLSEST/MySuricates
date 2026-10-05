package com.automation.controller;

import com.automation.dto.request.CreateSkillRequest;
import com.automation.dto.request.ImportSkillRequest;
import com.automation.dto.request.UpdateSkillRequest;
import com.automation.dto.response.CapabilityResponse;
import com.automation.dto.response.ExportSkillResponse;
import com.automation.dto.response.SkillResponse;
import com.automation.dto.response.SkillVersionResponse;
import com.automation.mapper.SkillMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.skill.SkillImportExportService;
import com.automation.service.skill.SkillService;
import com.automation.service.workflow.WorkflowSealStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Skills and their capabilities: list, create, edit, delete, version history and JSON import/export. */
@RequiresModule(ModuleCatalog.SKILLS)
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;
    private final SkillImportExportService importExportService;
    private final SkillMapper skillMapper;
    private final WorkflowSealStatusService sealStatusService;

    /** Lists all skills, built-in and custom, with their capabilities. */
    @GetMapping
    public List<SkillResponse> listSkills() {
        return skillMapper.toResponseList(skillService.findAll());
    }

    /** skill id → names of the sealed workflows that use it; such skills cannot be changed or deleted. */
    @GetMapping("/seal-pins")
    public Map<String, List<String>> sealPins() {
        return sealStatusService.sealedWorkflowsBySkill();
    }

    /** Returns one skill with its capabilities; unknown id is 404. */
    @GetMapping("/{id}")
    public SkillResponse getSkill(@PathVariable String id) {
        return skillMapper.toResponse(skillService.findById(id));
    }

    /** Lists the capabilities of one skill. */
    @GetMapping("/{id}/capabilities")
    public List<CapabilityResponse> getCapabilities(@PathVariable String id) {
        return skillMapper.toCapabilityResponseList(skillService.findCapabilities(id));
    }

    /** Returns the change history of a skill, newest first, also for a skill deleted since; the snapshots are not included. */
    @GetMapping("/{id}/versions")
    public List<SkillVersionResponse> getVersions(@PathVariable String id) {
        return skillService.findVersions(id).stream().map(SkillVersionResponse::from).toList();
    }

    /** Creates a custom skill and answers 201; {@code name} and {@code category} are required. */
    @PostMapping
    public ResponseEntity<SkillResponse> createSkill(@Valid @RequestBody CreateSkillRequest request) {
        var skill = skillService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(skillMapper.toResponse(skill));
    }

    /**
     * Updates a custom skill.
     * Custom skills only (built-in → 409); a skill used by a sealed workflow → 409 SKILL_PINNED_BY_SEAL.
     * Only name, description, icon, category and {@code aiContext} change; {@code configSchema}, the
     * capabilities and the version label stay as they are.
     */
    @PutMapping("/{id}")
    public SkillResponse updateSkill(@PathVariable String id, @Valid @RequestBody UpdateSkillRequest request) {
        return skillMapper.toResponse(skillService.update(id, request));
    }

    /**
     * Deletes a custom skill (204).
     * A built-in skill or one used by a sealed workflow is refused with 409.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable String id) {
        skillService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Exports a skill with its capabilities as JSON in the format the import accepts. */
    @GetMapping("/export/{id}")
    public ExportSkillResponse exportSkill(@PathVariable String id) {
        return importExportService.exportSkill(id);
    }

    /** Imports a skill from its exported JSON as a new custom skill and answers 201; every capability needs a {@code name}. */
    @PostMapping("/import")
    public ResponseEntity<SkillResponse> importSkill(@Valid @RequestBody ImportSkillRequest request) {
        var skill = importExportService.importSkill(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(skillMapper.toResponse(skill));
    }
}
