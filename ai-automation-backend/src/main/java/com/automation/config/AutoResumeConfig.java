package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Automatic resume of runs that a restart or a system standby interrupted (TODO 90d10ba1). Each
 * workflow opts in on its own ({@code metadata.autoResume.enabled}); this is the global side.
 * Both values are also runtime settings ({@code run-auto-resume-enabled},
 * {@code run-auto-resume-delay-seconds}).
 */
@Configuration
@ConfigurationProperties(prefix = "automation.execution.auto-resume")
@Getter
@Setter
public class AutoResumeConfig {

    /** Global switch. Off: no run is resumed automatically, whatever the workflows opted in to. */
    private boolean enabled = true;

    /**
     * Wait before each automatic resume, the first one included (seconds). Resumes run one after
     * another with this gap, so a restart does not start every opted-in run at the same moment.
     */
    private long delaySeconds = 30;
}
