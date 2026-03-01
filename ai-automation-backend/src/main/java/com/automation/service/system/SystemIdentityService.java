package com.automation.service.system;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SystemIdentityService {

    private static final String NAME = "MySuricates";

    @Value("${app.version:0.1.0-SNAPSHOT}")
    private String rawVersion;

    @Value("${app.build-timestamp:unknown}")
    @Getter
    private String buildTimestamp;

    @Getter
    private String version;

    @Getter
    private String buildType;

    @PostConstruct
    public void init() {
        int dashIdx = rawVersion.indexOf('-');
        if (dashIdx > 0) {
            version = rawVersion.substring(0, dashIdx);
            buildType = rawVersion.substring(dashIdx + 1);
        } else {
            version = rawVersion;
            buildType = "RELEASE";
        }
        log.info("{} v{} ({}) initialized", NAME, version, buildType);
    }

    public String getName() {
        return NAME;
    }

    public String getVersionedName() {
        return NAME + " v" + version;
    }

    public String getGitTrailer() {
        return "Generated-By: " + getVersionedName();
    }

    public String getAgentIdentityBlock() {
        return """
                ## System Identity
                Du bist ein Agent von %s, einer KI-gesteuerten Workflow-Automations-Plattform.

                Wenn du Git-Commits erstellst, fuege diesen Trailer ans Ende der Commit-Nachricht an:
                %s
                """.formatted(getVersionedName(), getGitTrailer());
    }
}
