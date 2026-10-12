package com.automation.service.wish;

import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.WishRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;

@Slf4j
final class WishStatusHelper {

    private WishStatusHelper() {}

    static void broadcastStatus(SimpMessagingTemplate messagingTemplate, String wishId, Wish wish) {
        try {
            Map<String, Object> payload = Map.of(
                    "wishId", wishId,
                    "status", wish.getStatus().name(),
                    "iterationCount", wish.getIterationCount(),
                    "maxIterations", wish.getMaxIterations()
            );
            messagingTemplate.convertAndSend("/topic/wishes/" + wishId + "/status", (Object) payload);
        } catch (Exception e) {
            log.debug("Failed to broadcast wish status", e);
        }
    }

    static void updateStatus(WishRepository wishRepository, SimpMessagingTemplate messagingTemplate, Wish wish, WishStatus status) {
        wish.setStatus(status);
        wishRepository.save(wish);
        broadcastStatus(messagingTemplate, wish.getId(), wish);
    }

    @SuppressWarnings("unchecked")
    static void updateCurrentIterationEntry(Wish wish, String key, Object value) {
        List<Map<String, Object>> history = new ArrayList<>(wish.getIterationHistory());
        if (!history.isEmpty()) {
            Map<String, Object> current = new HashMap<>(history.get(history.size() - 1));
            current.put(key, value);
            history.set(history.size() - 1, current);
            wish.setIterationHistory(history);
        }
    }

    static boolean isTerminal(WishStatus status) {
        return status == WishStatus.COMPLETED || status == WishStatus.FAILED || status == WishStatus.CANCELLED;
    }

    static boolean isPaused(WishStatus status) {
        return status == WishStatus.AWAITING_APPROVAL
                || status == WishStatus.AWAITING_CLARIFICATION
                || status == WishStatus.AWAITING_DECOMPOSE_APPROVAL;
    }
}
