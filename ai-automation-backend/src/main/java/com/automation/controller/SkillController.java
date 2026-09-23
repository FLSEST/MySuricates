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

@RequiresModule(ModuleCatalog.SKILLS)
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;
    private final SkillImportExportService importExportService;
    private final SkillMapper skillMapper;
    private final WorkflowSealStatusService sealStatusService;

    @GetMapping
    public List<SkillResponse> listSkills() {
        return skillMapper.toResponseList(skillService.findAll());
    }

    /** skill id → names of the sealed workflows that use it; such skills cannot be changed or deleted. */
    @GetMapping("/seal-pins")
    public Map<String, List<String>> sealPins() {
        return sealStatusService.sealedWorkflowsBySkill();
    }

    @GetMapping("/{id}")
    public SkillResponse getSkill(@PathVariable String id) {
        return skillMapper.toResponse(skillService.findById(id));
    }

    @GetMapping("/{id}/capabilities")
    public List<CapabilityResponse> getCapabilities(@PathVariable String id) {
        return skillMapper.toCapabilityResponseList(skillService.findCapabilities(id));
    }

    @GetMapping("/{id}/versions")
    public List<SkillVersionResponse> getVersions(@PathVariable String id) {
        return skillService.findVersions(id).stream().map(SkillVersionResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<SkillResponse> createSkill(@Valid @RequestBody CreateSkillRequest request) {
        var skill = skillService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(skillMapper.toResponse(skill));
    }

    /** Custom skills only (built-in → 409); a skill used by a sealed workflow → 409 SKILL_PINNED_BY_SEAL. */
    @PutMapping("/{id}")
    public SkillResponse updateSkill(@PathVariable String id, @Valid @RequestBody UpdateSkillRequest request) {
        return skillMapper.toResponse(skillService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable String id) {
        skillService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/export/{id}")
    public ExportSkillResponse exportSkill(@PathVariable String id) {
        return importExportService.exportSkill(id);
    }

    @PostMapping("/import")
    public ResponseEntity<SkillResponse> importSkill(@Valid @RequestBody ImportSkillRequest request) {
        var skill = importExportService.importSkill(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(skillMapper.toResponse(skill));
    }
}
