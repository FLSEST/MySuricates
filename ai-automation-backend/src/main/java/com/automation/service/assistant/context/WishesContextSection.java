package com.automation.service.assistant.context;

import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.WishRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class WishesContextSection implements AssistantContextSection {

    private final WishRepository wishRepository;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            List<WishStatus> activeStatuses = List.of(
                    WishStatus.RECEIVED, WishStatus.DECOMPOSING,
                    WishStatus.AWAITING_DECOMPOSE_APPROVAL, WishStatus.AWAITING_CLARIFICATION,
                    WishStatus.AWAITING_APPROVAL, WishStatus.EXECUTING,
                    WishStatus.EVALUATING, WishStatus.REFINING
            );
            List<Wish> allWishes = wishRepository.findAllByOrderByCreatedAtDesc();
            List<Wish> activeWishes = allWishes.stream()
                    .filter(w -> activeStatuses.contains(w.getStatus()))
                    .limit(5).toList();
            List<Wish> failedWishes = allWishes.stream()
                    .filter(w -> w.getStatus() == WishStatus.FAILED || w.getStatus() == WishStatus.CANCELLED)
                    .limit(5).toList();

            if (compact) {
                boolean hasAny = !activeWishes.isEmpty() || !failedWishes.isEmpty();
                if (hasAny) {
                    String failedIds = failedWishes.stream()
                            .map(w -> w.getId().substring(0, Math.min(8, w.getId().length())))
                            .collect(Collectors.joining(", "));
                    sb.append("**Wishes:** ").append(activeWishes.size()).append(" aktiv, ")
                            .append(failedWishes.size()).append(" fehlgeschlagen");
                    if (!failedIds.isEmpty()) sb.append(" (ids: ").append(failedIds).append(")");
                    sb.append("\n");
                }
                return;
            }

            if (!activeWishes.isEmpty()) {
                sb.append("**Aktive Wishes (").append(activeWishes.size()).append("):**\n");
                activeWishes.forEach(w -> sb.append("  - [").append(w.getStatus()).append("] ")
                        .append(truncate(w.getOriginalText(), 80)).append("\n"));
                sb.append("\n");
            }

            if (!failedWishes.isEmpty()) {
                sb.append("**Letzte fehlgeschlagene Wishes:**\n");
                failedWishes.forEach(w -> {
                    sb.append("  - [").append(w.getStatus()).append("] ")
                            .append(truncate(w.getOriginalText(), 80));
                    if (w.getError() != null) sb.append(" — ").append(truncate(w.getError(), 100));
                    sb.append("\n");
                });
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Could not load wishes for assistant context", e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s != null ? s : "";
        return s.substring(0, max) + "…";
    }
}
