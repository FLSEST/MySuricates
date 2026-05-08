package com.automation.service.skill;

import com.automation.dto.request.ImportSkillRequest;
import com.automation.dto.response.ExportSkillResponse;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SkillImportExportService {

    private final SkillRepository skillRepository;
    private final SkillRegistry skillRegistry;

    @Transactional(readOnly = true)
    public ExportSkillResponse exportSkill(String skillId) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new ResourceNotFoundException("Skill", skillId));

        List<ExportSkillResponse.ExportCapabilityResponse> capabilities = skill.getCapabilities().stream()
                .map(c -> new ExportSkillResponse.ExportCapabilityResponse(
                        c.getName(),
                        c.getDescription(),
                        c.getHandlerClass(),
                        c.getInputSchema(),
                        c.getOutputSchema(),
                        c.isAsync(),
                        c.isWrites()
                ))
                .toList();

        return new ExportSkillResponse(
                skill.getName(),
                skill.getDescription(),
                skill.getIcon(),
                skill.getCategory(),
                skill.getAiContext(),
                skill.getConfigSchema(),
                skill.getVersion(),
                capabilities
        );
    }

    @Transactional
    public Skill importSkill(ImportSkillRequest request) {
        Skill skill = Skill.builder()
                .name(request.name())
                .description(request.description())
                .icon(request.icon())
                .category(request.category())
                .aiContext(request.aiContext())
                .configSchema(request.configSchema() != null ? request.configSchema() : new HashMap<>())
                .version(request.version() != null ? request.version() : "1.0.0")
                .builtin(false)
                .capabilities(new ArrayList<>())
                .build();

        if (request.capabilities() != null) {
            for (var capReq : request.capabilities()) {
                Capability capability = Capability.builder()
                        .skill(skill)
                        .name(capReq.name())
                        .description(capReq.description())
                        .handlerClass(capReq.handlerClass())
                        .inputSchema(capReq.inputSchema() != null ? capReq.inputSchema() : new HashMap<>())
                        .outputSchema(capReq.outputSchema() != null ? capReq.outputSchema() : new HashMap<>())
                        .async(capReq.async())
                        .writes(capReq.writes())
                        .build();
                skill.getCapabilities().add(capability);
            }
        }

        Skill saved = skillRepository.save(skill);
        skillRegistry.register(saved);

        log.info("Imported skill: {} ({}) with {} capabilities",
                saved.getName(), saved.getId(), saved.getCapabilities().size());
        return saved;
    }
}
