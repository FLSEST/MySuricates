package com.automation.service.project;

import com.github.dockerjava.api.DockerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ob Docker auf diesem Rechner eingerichtet ist — und ob er antwortet.
 *
 * <p>Eine Installation ohne Docker ist ein Normalzustand: Shell-Bloecke und KI-Aufrufe laufen auf dem
 * Host, nur Container-Shell-Bloecke, Run-Terminal und Stable/Test brauchen Docker. Ohne diese
 * Unterscheidung meldete jede frische Instanz ohne Docker „gestoert“ und jeder Run einen ERROR
 * (a0e85182). Eingerichtet heisst: ein TCP-Ziel ist konfiguriert, oder der Unix-Socket existiert.
 * Existiert er, antwortet aber nicht, ist das weiterhin ein Fehler ({@link State#DOWN}).
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

    /** Pingt Docker; ein Fehlschlag ist nur dann {@link State#DOWN}, wenn Docker eingerichtet ist. */
    public State probe() {
        try {
            dockerClient.pingCmd().exec();
            return State.UP;
        } catch (Exception | Error e) {
            return isConfigured() ? State.DOWN : State.NOT_CONFIGURED;
        }
    }
}
