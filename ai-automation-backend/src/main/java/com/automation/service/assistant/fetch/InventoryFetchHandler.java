package com.automation.service.assistant.fetch;

import com.automation.entity.IntegrationConnection;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.assistant.AssistantFormatUtils;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationConnectionNames;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.IntegrationVerification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fetch handler for the platform inventory (repertoire) details: wishes, skills,
 * integrations and chains ({@code wish_detail}, {@code skill}, {@code integration},
 * {@code chain}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryFetchHandler {

    private final WishRepository wishRepository;
    private final SkillRepository skillRepository;
    private final IntegrationRegistry integrationRegistry;
    private final IntegrationConnectionRepository integrationConnectionRepository;
    private final WorkflowChainRepository workflowChainRepository;
    private final WorkflowRepository workflowRepository;

    public String formatWishDetail(String id) {
        try {
            return wishRepository.findById(id).map(w -> {
                var sb = new StringBuilder();
                sb.append("**Wish ").append(id, 0, Math.min(8, id.length())).append("…** — [")
                        .append(w.getStatus()).append("] ").append(AssistantFormatUtils.truncate(w.getOriginalText(), 200))
                        .append("\n");
                sb.append("Iterations: ").append(w.getIterationCount())
                        .append("/").append(w.getMaxIterations()).append("\n");
                if (w.getError() != null)
                    sb.append("Error: ").append(AssistantFormatUtils.truncate(w.getError(), 300)).append("\n");
                return sb.toString();
            }).orElse("Wish " + id + " not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch wish detail for {}", id, e);
            return "";
        }
    }

    public String formatSkillDetail(String skillId) {
        try {
            return skillRepository.findByIdWithCapabilities(skillId).map(s -> {
                var sb = new StringBuilder();
                sb.append("**Skill: ").append(s.getName()).append("** (id: ").append(s.getId()).append(")\n");
                sb.append("Category: ").append(s.getCategory()).append("\n");
                if (s.getDescription() != null && !s.getDescription().isBlank()) {
                    sb.append("Description: ").append(AssistantFormatUtils.truncate(s.getDescription().trim(), 200)).append("\n");
                }
                if (s.getAiContext() != null && !s.getAiContext().isBlank()) {
                    sb.append("AI context: ").append(AssistantFormatUtils.truncate(s.getAiContext().trim(), 400)).append("\n");
                }
                var caps = s.getCapabilities();
                if (caps != null && !caps.isEmpty()) {
                    sb.append("\nCapabilities (").append(caps.size()).append("):\n");
                    caps.forEach(c -> {
                        sb.append("  - ").append(c.getName());
                        if (c.getDescription() != null && !c.getDescription().isBlank()) {
                            sb.append(" — ").append(AssistantFormatUtils.truncate(c.getDescription().trim(), 100));
                        }
                        sb.append("\n");
                        if (c.getInputSchema() != null && !c.getInputSchema().isEmpty()) {
                            sb.append("    inputSchema keys: ").append(c.getInputSchema().keySet()).append("\n");
                        }
                    });
                }
                return sb.toString();
            }).orElse("Skill '" + skillId + "' not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch skill detail for {}", skillId, e);
            return "";
        }
    }

    public String formatIntegrationDetail(String providerId) {
        try {
            if (!integrationRegistry.hasProvider(providerId)) {
                return "Integration '" + providerId + "' not found.\n";
            }
            IntegrationProvider p = integrationRegistry.getProvider(providerId);
            List<IntegrationConnection> connections = integrationConnectionRepository.findAllByProviderId(providerId);
            IntegrationConnection defaultConnection = connections.stream()
                    .filter(c -> IntegrationConnectionNames.isDefault(c.getConnectionName()))
                    .findFirst().orElse(null);
            IntegrationConnection.ConnectionStatus status = defaultConnection != null
                    ? defaultConnection.getStatus() : IntegrationConnection.ConnectionStatus.DISCONNECTED;
            String accountInfo = defaultConnection != null ? defaultConnection.getAccountInfo() : null;
            var sb = new StringBuilder();
            sb.append("**Integration: ").append(p.getDisplayName()).append("** (id: ").append(p.getProviderId()).append(")\n");
            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                sb.append("Description: ").append(AssistantFormatUtils.truncate(p.getDescription().trim(), 200)).append("\n");
            }
            sb.append("Status: ").append(status);
            if (accountInfo != null && !accountInfo.isBlank()) {
                sb.append(" (").append(AssistantFormatUtils.truncate(accountInfo, 80)).append(")");
            }
            sb.append("\n");
            List<String> named = connections.stream()
                    .filter(c -> !IntegrationConnectionNames.isDefault(c.getConnectionName()))
                    .sorted(java.util.Comparator.comparing(IntegrationConnection::getConnectionName))
                    .map(c -> c.getConnectionName() + " (" + c.getStatus() + ")")
                    .toList();
            if (!named.isEmpty()) {
                sb.append("Named connections (block config `connection`): ").append(String.join(", ", named)).append("\n");
            }
            sb.append("OAuth flow required: ").append(p.requiresOAuthFlow() ? "yes" : "no (API key/token)").append("\n");
            sb.append("Verification: ").append(p.getVerification() == IntegrationVerification.VERIFIED
                    ? "checked against the real service (" + p.getVerifiedAgainst() + ")"
                    : "simulated only — not yet checked against the real service").append("\n");
            List<IntegrationAction> actions = p.getAvailableActions();
            if (actions != null && !actions.isEmpty()) {
                sb.append("\nAvailable actions (").append(actions.size()).append("):\n");
                actions.forEach(a -> {
                    sb.append("  - ").append(a.name());
                    if (a.description() != null && !a.description().isBlank()) {
                        sb.append(" — ").append(AssistantFormatUtils.truncate(a.description().trim(), 100));
                    }
                    sb.append("\n");
                    if (a.inputSchema() != null && !a.inputSchema().isEmpty()) {
                        sb.append("    inputSchema keys: ").append(a.inputSchema().keySet()).append("\n");
                    }
                    if (a.requiredScopes() != null && !a.requiredScopes().isEmpty()) {
                        sb.append("    scopes: ").append(a.requiredScopes()).append("\n");
                    }
                });
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not fetch integration detail for {}", providerId, e);
            return "";
        }
    }

    public String formatChainDetail(String chainId) {
        try {
            return workflowChainRepository.findById(chainId).map(c -> {
                var sb = new StringBuilder();
                sb.append("**Chain: ").append(c.getName()).append("** (id: ").append(c.getId()).append(")\n");
                if (c.getDescription() != null && !c.getDescription().isBlank()) {
                    sb.append("Description: ").append(AssistantFormatUtils.truncate(c.getDescription().trim(), 200)).append("\n");
                }
                sb.append("Stop on failure: ").append(c.isStopOnFailure() ? "yes" : "no").append("\n");
                var entries = c.getEntries();
                if (entries != null && !entries.isEmpty()) {
                    sb.append("\nWorkflows in Chain (").append(entries.size()).append("):\n");
                    Map<String, String> names = workflowNames(entries.stream()
                            .map(e -> e.getWorkflowId()).filter(Objects::nonNull).distinct().toList());
                    for (int i = 0; i < entries.size(); i++) {
                        var entry = entries.get(i);
                        String wfName = names.getOrDefault(entry.getWorkflowId(), entry.getWorkflowId());
                        sb.append("  ").append(i + 1).append(". ").append(wfName)
                          .append(" (id: ").append(entry.getWorkflowId()).append(")");
                        if (entry.getLabel() != null && !entry.getLabel().isBlank()) {
                            sb.append(" [Label: ").append(entry.getLabel()).append("]");
                        }
                        sb.append("\n");
                        if (entry.getInputContext() != null && !entry.getInputContext().isEmpty()) {
                            sb.append("    inputs: ").append(entry.getInputContext().keySet()).append("\n");
                        }
                    }
                }
                return sb.toString();
            }).orElse("Chain '" + chainId + "' not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch chain detail for {}", chainId, e);
            return "";
        }
    }

    /** Names of the chain's workflows in one query instead of one lookup per entry (TODO ad130b6f). */
    private Map<String, String> workflowNames(List<String> workflowIds) {
        if (workflowIds.isEmpty()) return Map.of();
        Map<String, String> names = new HashMap<>();
        for (Object[] row : workflowRepository.findIdAndNameByIdIn(workflowIds)) {
            if (row[1] != null) names.put((String) row[0], (String) row[1]);
        }
        return names;
    }
}
