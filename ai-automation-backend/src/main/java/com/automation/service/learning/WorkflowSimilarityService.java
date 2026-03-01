package com.automation.service.learning;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkflowSimilarityService {

    private final WorkflowRepository workflowRepository;

    private final Map<String, CachedResult> similarWorkflowsCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60_000;

    private record CachedResult(List<Map<String, Object>> data, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSimilarWorkflows(String workflowId) {
        CachedResult cached = similarWorkflowsCache.get(workflowId);
        if (cached != null && !cached.isExpired()) {
            return cached.data();
        }

        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));

        String blockTypesStr = workflow.getBlockTypes();
        Set<String> blockTypes;
        if (blockTypesStr != null && !blockTypesStr.isEmpty()) {
            blockTypes = new HashSet<>(Arrays.asList(blockTypesStr.split(",")));
        } else {
            blockTypes = workflow.getBlocks().stream()
                    .map(BlockDefinition::getType)
                    .collect(Collectors.toSet());
        }

        if (blockTypes.isEmpty()) return List.of();

        List<Object[]> rows = workflowRepository.findAllIdNameBlockTypes(workflowId);

        List<Map<String, Object>> similar = new ArrayList<>();
        for (Object[] row : rows) {
            String otherId = (String) row[0];
            String otherName = (String) row[1];
            String otherBlockTypesStr = (String) row[2];

            if (otherBlockTypesStr == null || otherBlockTypesStr.isEmpty()) continue;

            Set<String> otherTypes = new HashSet<>(Arrays.asList(otherBlockTypesStr.split(",")));
            double similarity = calculateSetSimilarity(blockTypes, otherTypes);

            if (similarity > 0.5) {
                similar.add(Map.of(
                        "workflowId", otherId,
                        "workflowName", otherName,
                        "similarity", Math.round(similarity * 100),
                        "blockTypes", List.copyOf(otherTypes)
                ));
            }
        }

        similar.sort((a, b) -> Long.compare((Long) b.get("similarity"), (Long) a.get("similarity")));

        List<Map<String, Object>> result = similar.size() > 20 ? similar.subList(0, 20) : similar;

        similarWorkflowsCache.put(workflowId, new CachedResult(result, System.currentTimeMillis()));

        return result;
    }

    double calculateSetSimilarity(Set<String> setA, Set<String> setB) {
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        return (double) intersection.size() / union.size();
    }
}
