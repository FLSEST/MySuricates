package com.automation.service.project;

import com.github.dockerjava.api.DockerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether Docker is set up on this machine - and whether it responds.
 *
 * <p>An installation without Docker is a normal state: shell blocks and AI calls run on the
 * host, only container shell blocks, run terminal and stable/test need Docker. Without this
 * distinction every fresh instance without Docker reported "disturbed" and every run an ERROR
 * (a0e85182). Set up means: a TCP target is configured, or the Unix socket exists.
 * If it exists but does not respond, that is still an error ({@link State#DOWN}).
 */
@Component
public class DockerExpectation {

    public enum State { UP, DOWN, NOT_CONFIGURED }

    private final DockerClient dockerClient;
    private final String dockerHost;

    public DockerExpectation(DockerClient dockerClient,
                             @Value("${docker.host:unix:///var/run/docker.sock}") String dockerHost) {
        this.dockerClient = dockerClient;
        this.dockerHost = dockerHost;
    }

    public boolean isConfigured() {
        if (dockerHost == null || dockerHost.isBlank()) return false;
        if (dockerHost.startsWith("unix://")) {
            return Files.exists(Path.of(dockerHost.substring("unix://".length())));
        }
        return true;
    }

    /** Pings Docker; a failure is only {@link State#DOWN} if Docker is set up. */
    public State probe() {
        try {
            dockerClient.pingCmd().exec();
            return State.UP;
        } catch (Exception | Error e) {
            return isConfigured() ? State.DOWN : State.NOT_CONFIGURED;
        }
    }
}
