package com.automation.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.SchedulingConfiguration;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures that no Spring scheduling is active in the "integration" profile.
 *
 * <p>Background: running {@code @Scheduled} jobs write to the same SQLite file as the tests during
 * the test run. {@code HealthAutoTodoService} thus created a TODO as soon as the stable/test
 * instance was unreachable, which broke globally counting assertions in
 * {@code TodoRepositoryIntegrationTest}. See {@code config.SchedulingConfig}.
 */
@SpringBootTest
@ActiveProfiles("integration")
class SchedulingDisabledIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulingIsNotEnabledUnderIntegrationProfile() {
        assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .as("kein ScheduledAnnotationBeanPostProcessor -> keine @Scheduled-Jobs im Testkontext")
                .isFalse();
        assertThat(context.getBeanNamesForType(SchedulingConfiguration.class))
                .as("SchedulingConfig darf unter dem Profil 'integration' nicht greifen")
                .isEmpty();
    }
}
