package com.automation.service.assistant.context;

import com.automation.entity.WorkflowChain;
import com.automation.repository.WorkflowChainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChainsIndexSection implements AssistantContextSection {

    private static final int MAX_ENTRIES = 15;

    private final WorkflowChainRepository chainRepository;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            List<WorkflowChain> chains = chainRepository.findAll();
            if (chains.isEmpty()) {
                return;
            }
            if (compact) {
                sb.append("**Chains:** ").append(chains.size()).append(" available\n");
                return;
            }
            int shown = Math.min(chains.size(), MAX_ENTRIES);
            sb.append("**Chains (").append(chains.size()).append("):**\n");
            chains.stream().limit(MAX_ENTRIES).forEach(c -> sb
                    .append("  - ").append(c.getName())
                    .append(" — ").append(shortDesc(c.getDescription()))
                    .append(" (").append(c.getEntries() != null ? c.getEntries().size() : 0)
                    .append(" workflows, id: ").append(c.getId()).append(")\n"));
            if (chains.size() > shown) {
                sb.append("  … and ").append(chains.size() - shown)
                  .append(" more. Detail via <fetch type=\"chain\" id=\"<chain-id>\"/>\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not load chains for assistant context", e);
        }
    }

    private String shortDesc(String desc) {
        if (desc == null || desc.isBlank()) return "(no description)";
        String trimmed = desc.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 77) + "..." : trimmed;
    }
}
