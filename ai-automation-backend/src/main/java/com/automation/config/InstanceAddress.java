package com.automation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * How agents on this machine reach this instance and read its API key — filled into the texts
 * MySuricates writes into projects (onboarding profiles, TODO a805d0a7).
 *
 * <p>The address comes from what the installer chose, not from a guess: {@code scripts/instance.sh}
 * (host) and {@code deploy/compose.yml} (container) pass {@code AUTOMATION_INSTANCE_URL} from the port
 * in {@code deploy/instances/<name>.env}. Inside a container the backend listens on 9090 and cannot
 * know the published port itself. Without the setting (dev instance, containers publishing the same
 * port) the address is {@code http://localhost:<server.port>}. {@code localhost} also holds for a
 * public bind address: the agents work on the machine the project lives on.
 *
 * <p>{@code AUTOMATION_INSTANCE_CONTAINER} names the container the backend runs in; the API key file
 * then only exists inside it.
 */
@Component
public class InstanceAddress {

    private final String url;
    private final String container;
    private final int port;

    public InstanceAddress(@Value("${automation.instance.url:}") String url,
                           @Value("${automation.instance.container:}") String container,
                           @Value("${server.port:9090}") int port) {
        this.url = url == null ? "" : url.strip();
        this.container = container == null ? "" : container.strip();
        this.port = port;
    }

    /** The file the API key is written to: {@code user.home} of this JVM (per instance under the installer). */
    public static Path apiKeyFile() {
        return DataHome.dir().resolve("api-key");
    }

    /** Base URL without a trailing slash, e.g. {@code http://localhost:9090}. */
    public String baseUrl() {
        String base = url.isEmpty() ? "http://localhost:" + port : url;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * Shell command that prints the API key on this machine. In a container the file only exists
     * inside it, so the command reads it through {@code docker exec} (the exec user is root; the
     * backend itself runs as the host user — both may read the file).
     */
    public String apiKeyCommand() {
        String file = "'" + apiKeyFile() + "'";
        return container.isEmpty() ? "cat " + file : "docker exec " + container + " cat " + file;
    }

    /** Replaces {@code {{BASE_URL}}} and {@code {{API_KEY_COMMAND}}} in a text written for agents. */
    public String fill(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("{{BASE_URL}}", baseUrl()).replace("{{API_KEY_COMMAND}}", apiKeyCommand());
    }
}
