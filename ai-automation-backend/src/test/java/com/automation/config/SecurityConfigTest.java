package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(SecurityConfig.class.isAnnotationPresent(Configuration.class))
                .as("SecurityConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveEnableWebSecurityAnnotation_when_classIsInspected() {
        assertThat(SecurityConfig.class.isAnnotationPresent(EnableWebSecurity.class))
                .as("SecurityConfig must be annotated with @EnableWebSecurity")
                .isTrue();
    }

    // --- Bean method tests ---

    @Test
    void should_haveFilterChainMethod_when_classIsInspected() throws NoSuchMethodException {
        Method method = SecurityConfig.class.getDeclaredMethod(
                "filterChain", org.springframework.security.config.annotation.web.builders.HttpSecurity.class);
        assertThat(method).isNotNull();
    }

    @Test
    void should_haveFilterChainMethodAnnotatedWithBean_when_classIsInspected() throws NoSuchMethodException {
        Method method = SecurityConfig.class.getDeclaredMethod(
                "filterChain", org.springframework.security.config.annotation.web.builders.HttpSecurity.class);
        assertThat(method.isAnnotationPresent(Bean.class))
                .as("filterChain method must be annotated with @Bean")
                .isTrue();
    }

    @Test
    void should_returnSecurityFilterChain_when_filterChainReturnTypeIsInspected() throws NoSuchMethodException {
        Method method = SecurityConfig.class.getDeclaredMethod(
                "filterChain", org.springframework.security.config.annotation.web.builders.HttpSecurity.class);
        assertThat(method.getReturnType()).isAssignableTo(SecurityFilterChain.class);
    }

    // --- Constructor dependency tests ---

    @Test
    void should_haveConstructorWithApiKeyAuthFilter_when_constructorsAreInspected() {
        boolean found = false;
        for (var constructor : SecurityConfig.class.getDeclaredConstructors()) {
            var params = constructor.getParameterTypes();
            if (params.length == 1 && params[0].getSimpleName().equals("ApiKeyAuthFilter")) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("SecurityConfig must have a constructor accepting ApiKeyAuthFilter")
                .isTrue();
    }
}
