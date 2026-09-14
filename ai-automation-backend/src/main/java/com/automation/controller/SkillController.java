package com.automation.controller;

import com.automation.dto.request.CreateSkillRequest;
import com.automation.dto.request.ImportSkillRequest;
import com.automation.dto.response.CapabilityResponse;
import com.automation.dto.response.ExportSkillResponse;
import com.automation.dto.response.SkillResponse;
import com.automation.mapper.SkillMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.skill.SkillImportExportService;
import com.automation.service.skill.SkillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiresModule(ModuleCatalog.SKILLS)
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;
    private final SkillImportExportService importExportService;
    private final SkillMapper skillMapper;

    @GetMapping
    public List<SkillResponse> listSkills() {
        return skillMapper.toResponseList(skillService.findAll());
    }

    @GetMapping("/{id}")
    public SkillResponse getSkill(@PathVariable String id) {
        return skillMapper.toResponse(skillService.findById(id));
    }

    @GetMapping("/{id}/capabilities")
    public List<CapabilityResponse> getCapabilities(@PathVariable String id) {
        return skillMapper.toCapabilityResponseList(skillService.findCapabilities(id));
    }

    @PostMapping
    public ResponseEntity<SkillResponse> createSkill(@Valid @RequestBody CreateSkillRequest request) {
        var skill = skillService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(skillMapper.toResponse(skill));
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
