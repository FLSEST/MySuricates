package com.automation.service.assistant.context;

import com.automation.entity.IntegrationConnection;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrationsIndexSection implements AssistantContextSection {

    private final IntegrationRegistry integrationRegistry;
    private final IntegrationConnectionRepository connectionRepository;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            Collection<IntegrationProvider> providers = integrationRegistry.getAllProviders();
            if (providers.isEmpty()) {
                return;
            }
            Map<String, IntegrationConnection.ConnectionStatus> statusByProvider = loadStatusMap();
            long connected = providers.stream()
                    .filter(p -> statusByProvider.get(p.getProviderId()) == IntegrationConnection.ConnectionStatus.CONNECTED)
                    .count();
            if (compact) {
                sb.append("**Integrationen:** ").append(providers.size())
                  .append(" Provider (").append(connected).append(" verbunden)\n");
                return;
            }
            sb.append("**Integrationen (").append(providers.size()).append("):**\n");
            providers.forEach(p -> {
                IntegrationConnection.ConnectionStatus status = statusByProvider.get(p.getProviderId());
                String marker = status == IntegrationConnection.ConnectionStatus.CONNECTED ? "✓" : "✗";
                sb.append("  - [").append(marker).append("] ")
                  .append(p.getDisplayName())
                  .append(" (id: ").append(p.getProviderId()).append(") — ")
                  .append(shortDesc(p.getDescription())).append("\n");
            });
            sb.append("  Detail via <fetch type=\"integration\" id=\"<provider-id>\"/>\n\n");
        } catch (Exception e) {
            log.debug("Could not load integrations for assistant context", e);
        }
    }

    private Map<String, IntegrationConnection.ConnectionStatus> loadStatusMap() {
        try {
            return connectionRepository.findAll().stream()
                    .collect(Collectors.toMap(IntegrationConnection::getProviderId,
                            IntegrationConnection::getStatus,
                            (a, b) -> a));
        } catch (Exception e) {
            log.debug("Could not load integration connection statuses", e);
            return Map.of();
        }
    }

    private String shortDesc(String desc) {
        if (desc == null || desc.isBlank()) return "(keine Beschreibung)";
        String trimmed = desc.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 77) + "..." : trimmed;
    }
}
