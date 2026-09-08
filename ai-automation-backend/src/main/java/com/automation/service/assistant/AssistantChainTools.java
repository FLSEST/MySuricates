package com.automation.service.assistant;

import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.service.chain.ChainService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Confirmed assistant tool {@code create_chain}. Takes the sanitized JSON payload and returns
 * the user-facing result line ({@code ✓ …} on success, {@code ⚠ …} otherwise) — never throws.
 *
 * <p>Extracted from {@code AssistantToolExecutor} (Audit-TODO 9f166f55). Guardrails, audit and
 * dispatch stay in the executor.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantChainTools {

    private final ChainService chainService;
    private final ObjectMapper objectMapper;

    public String createChain(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String name = (String) params.get("name");
            if (name == null || name.isBlank())
                return "⚠ create_chain: name is a required field.";

            List<Map<String, Object>> rawEntries = params.containsKey("entries")
                    ? objectMapper.convertValue(params.get("entries"), new TypeReference<>() {})
                    : List.of();
            if (rawEntries.isEmpty())
                return "⚠ create_chain: entries must not be empty.";

            List<ChainEntryRequest> entries = rawEntries.stream().map(e -> new ChainEntryRequest(
                    (String) e.get("workflowId"),
                    (String) e.getOrDefault("label", null),
                    e.containsKey("sortOrder") ? ((Number) e.get("sortOrder")).intValue() : 0,
                    e.containsKey("stopOnFailure") ? (Boolean) e.get("stopOnFailure") : null,
                    e.containsKey("inputContext")
                            ? objectMapper.convertValue(e.get("inputContext"), new TypeReference<Map<String, Object>>() {})
                            : null
            )).toList();

            var request = new CreateChainRequest(
                    name,
                    (String) params.getOrDefault("description", null),
                    (String) params.getOrDefault("icon", "⛓️"),
                    entries,
                    params.containsKey("stopOnFailure") ? (Boolean) params.get("stopOnFailure") : true
            );
            var created = chainService.create(request);
            return "✓ Chain created: \"" + created.getName() + "\" with " + entries.size() + " workflows (ID: " + created.getId() + ")";
        } catch (Exception e) {
            log.warn("Assistant create_chain tool failed. Payload: {}", json, e);
            return "⚠ Could not create chain: " + e.getMessage();
        }
    }
}
