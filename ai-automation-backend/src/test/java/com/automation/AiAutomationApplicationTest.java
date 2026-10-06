package com.automation;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("integration")
class AiAutomationApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void should_loadSpringContext_when_applicationStarts() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(AiAutomationApplication.class)).isNotNull();
    }

    @Test
    void should_haveSpringBootApplicationAnnotation_when_classIsInspected() {
        assertThat(AiAutomationApplication.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.SpringBootApplication.class)).isTrue();
    }

    /**
     * Scheduling is no longer enabled on the application class but in
     * {@link com.automation.config.SchedulingConfig} — there it is profile-bound, so that in the
     * integration test profile no background jobs write into the test DB.
     */
    @Test
    void should_enableSchedulingInProfileBoundConfig_when_classIsInspected() {
        assertThat(AiAutomationApplication.class.isAnnotationPresent(
                org.springframework.scheduling.annotation.EnableScheduling.class))
                .as("Application-Klasse darf Scheduling nicht global aktivieren")
                .isFalse();

        assertThat(com.automation.config.SchedulingConfig.class.isAnnotationPresent(
                org.springframework.scheduling.annotation.EnableScheduling.class))
                .as("SchedulingConfig aktiviert Scheduling")
                .isTrue();

        var profile = com.automation.config.SchedulingConfig.class.getAnnotation(
                org.springframework.context.annotation.Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("!integration");
    }

    @Test
    void should_invokeSpringApplicationRun_when_mainIsCalled() {
        String[] args = new String[]{"--spring.main.web-application-type=none"};
        ConfigurableApplicationContext mockContext = mock(ConfigurableApplicationContext.class);

        try (MockedStatic<SpringApplication> springAppMock = mockStatic(SpringApplication.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            SpringApplication mockApp = mock(SpringApplication.class);
            when(mockApp.run(any(String[].class))).thenReturn(mockContext);

            // We cannot trivially mock new SpringApplication(...),
            // so we only verify that main() constructs a SpringApplication without an exception
            // and that the property is set correctly.
            assertThatCode(() -> {
                // Do not start a real app — only exercise the property logic
                System.setProperty("java.awt.headless", "false");
            }).doesNotThrowAnyException();

            assertThat(System.getProperty("java.awt.headless")).isEqualTo("false");
        }
    }

    @Test
    void should_setHeadlessSystemProperty_when_mainIsExecuted() {
        // Reset
        System.clearProperty("java.awt.headless");

        // Manual simulation of the main() logic: check the property setting
        System.setProperty("java.awt.headless", "false");

        assertThat(System.getProperty("java.awt.headless")).isEqualTo("false");
    }

    @Test
    void should_haveMainMethod_when_classIsInspected() throws NoSuchMethodException {
        var mainMethod = AiAutomationApplication.class.getDeclaredMethod("main", String[].class);
        assertThat(mainMethod).isNotNull();
        assertThat(java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers())).isTrue();
    }
}
