package com.automation.service.project;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Whether the docker CLI reaches a daemon (java.review 6731fe36). Container blocks start through the CLI,
 * which also follows a {@code docker context} (rootless Docker, Docker Desktop for Linux, Colima). The
 * docker-java ping behind {@link DockerExpectation} only knows {@code docker.host}: without
 * {@code DOCKER_HOST} it reported "not set up" on such machines, and {@link DockerAccess} turned away
 * blocks that {@code docker run} would have started.
 *
 * <p>Only asked when the ping fails, and the answer is kept for {@link #TTL}: an instance without Docker
 * would otherwise start one process per analyzer request and per container block.
 */
@Component
@Slf4j
public class DockerCliProbe {

    static final Duration TTL = Duration.ofSeconds(30);
    private static final long TIMEOUT_SECONDS = 5;

    private final Clock clock;
    private volatile Instant checkedAt;
    private volatile boolean lastAnswer;

    public DockerCliProbe() {
        this(Clock.systemUTC());
    }

    DockerCliProbe(Clock clock) {
        this.clock = clock;
    }

    /** {@code docker version} answered with a server version within five seconds. */
    public boolean reachable() {
        Instant now = clock.instant();
        Instant last = checkedAt;
        if (last != null && last.plus(TTL).isAfter(now)) {
            return lastAnswer;
        }
        boolean answer = ask();
        lastAnswer = answer;
        checkedAt = now;
        return answer;
    }

    /** One fixed command, no workflow content: the inherited environment carries DOCKER_HOST/DOCKER_CONTEXT. */
    boolean ask() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String output = new String(process.getInputStream().readAllBytes()).strip();
            return process.exitValue() == 0 && !output.isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("docker CLI probe failed: {}", e.getMessage());
            return false;
        }
    }
}
