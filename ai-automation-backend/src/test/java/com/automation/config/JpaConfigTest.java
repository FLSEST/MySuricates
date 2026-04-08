package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import static org.assertj.core.api.Assertions.assertThat;

class JpaConfigTest {

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(JpaConfig.class.isAnnotationPresent(Configuration.class))
                .as("JpaConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveEnableJpaAuditingAnnotation_when_classIsInspected() {
        assertThat(JpaConfig.class.isAnnotationPresent(EnableJpaAuditing.class))
                .as("JpaConfig must be annotated with @EnableJpaAuditing")
                .isTrue();
    }

    @Test
    void should_beInstantiable_when_defaultConstructorIsUsed() {
        JpaConfig config = new JpaConfig();
        assertThat(config).isNotNull();
    }

    @Test
    void should_haveExactlyTwoClassAnnotations_when_annotationsAreCounted() {
        // Ensure no unexpected annotations are added without updating tests
        assertThat(JpaConfig.class.getDeclaredAnnotations())
                .hasSize(2);
    }
}
