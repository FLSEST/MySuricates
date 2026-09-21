package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void should_enable_scheduling_but_exclude_the_integration_profile() {
        assertThat(SchedulingConfig.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
        assertThat(SchedulingConfig.class.getAnnotation(Profile.class).value()).containsExactly("!integration");
    }

    @Test
    void should_register_the_scheduled_annotation_processor_without_profile() {
        runner.run(ctx -> assertThat(ctx)
                .hasBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
                .hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void should_not_enable_scheduling_in_the_integration_profile() {
        // background jobs would write into the integration test DB and compete for the single connection
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("integration"))
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(SchedulingConfig.class);
                    assertThat(ctx).doesNotHaveBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
                });
    }

    @Test
    void should_stay_active_in_other_profiles() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("stable"))
                .run(ctx -> assertThat(ctx).hasBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
    }
}
