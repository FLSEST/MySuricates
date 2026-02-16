package com.automation.service.cli;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class CliSessionManager {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, CliSession> activeSessions = new ConcurrentHashMap<>();

    public CliSession getOrCreateSession(String containerId, String cliType) throws IOException {
        String sessionKey = containerId + ":" + cliType;
        return activeSessions.computeIfAbsent(sessionKey, key -> {
            CliSession session = new CliSession(containerId, cliType);
            try {
                session.start();
            } catch (IOException e) {
                throw new RuntimeException("Failed to start CLI session", e);
            }
            return session;
        });
    }

    public void closeSession(String containerId) {
        // Close all sessions (bash + claude) for this container
        activeSessions.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(containerId + ":")) {
                entry.getValue().close();
                return true;
            }
            return false;
        });
    }

    public boolean hasActiveSession(String containerId) {
        return activeSessions.entrySet().stream()
                .anyMatch(e -> e.getKey().startsWith(containerId + ":") && e.getValue().isAlive());
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanupIdleSessions() {
        activeSessions.entrySet().removeIf(entry -> {
            CliSession session = entry.getValue();
            if (!session.isAlive() || session.isIdle(IDLE_TIMEOUT)) {
                log.info("Cleaning up idle CLI session for container: {}", entry.getKey());
                session.close();
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down {} active CLI sessions", activeSessions.size());
        activeSessions.values().forEach(CliSession::close);
        activeSessions.clear();
    }
}
