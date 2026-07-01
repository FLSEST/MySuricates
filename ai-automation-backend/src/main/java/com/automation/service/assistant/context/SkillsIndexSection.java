package com.automation.service.assistant.context;

import com.automation.entity.Skill;
import com.automation.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SkillsIndexSection implements AssistantContextSection {

    private static final int MAX_ENTRIES = 20;

    private final SkillRepository skillRepository;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            List<Skill> skills = skillRepository.findAllWithCapabilities();
            if (skills.isEmpty()) {
                return;
            }
            if (compact) {
                sb.append("**Skills:** ").append(skills.size()).append(" available\n");
                return;
            }
            int shown = Math.min(skills.size(), MAX_ENTRIES);
            sb.append("**Skills (").append(skills.size()).append("):**\n");
            skills.stream().limit(MAX_ENTRIES).forEach(s -> sb
                    .append("  - [").append(s.getCategory()).append("] ")
                    .append(s.getName())
                    .append(" — ").append(shortDesc(s.getDescription()))
                    .append(" (").append(s.getCapabilities().size()).append(" capabilities, id: ").append(s.getId()).append(")\n"));
            if (skills.size() > shown) {
                sb.append("  … and ").append(skills.size() - shown)
                  .append(" more. Detail via <fetch type=\"skill\" id=\"<skill-id>\"/>\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not load skills for assistant context", e);
        }
    }

    private String shortDesc(String desc) {
        if (desc == null || desc.isBlank()) return "(no description)";
        String trimmed = desc.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 77) + "..." : trimmed;
    }
}
