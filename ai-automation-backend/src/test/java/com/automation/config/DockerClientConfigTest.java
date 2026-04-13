package com.automation.config;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class DockerClientConfigTest {

    private DockerClientConfig config;

    @BeforeEach
    void setUp() throws Exception {
        config = new DockerClientConfig();
        setDockerHost("unix:///var/run/docker.sock");
    }

    private void setDockerHost(String host) throws Exception {
        Field field = DockerClientConfig.class.getDeclaredField("dockerHost");
        field.setAccessible(true);
        field.set(config, host);
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(DockerClientConfig.class.isAnnotationPresent(Configuration.class))
                .as("DockerClientConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveBeanAnnotation_when_dockerClientMethodIsInspected() throws NoSuchMethodException {
        Method method = DockerClientConfig.class.getMethod("dockerClient");
        assertThat(method.isAnnotationPresent(Bean.class))
                .as("dockerClient() must be annotated with @Bean")
                .isTrue();
    }

    @Test
    void should_returnDockerClientType_when_dockerClientMethodIsInspected() throws NoSuchMethodException {
        Method method = DockerClientConfig.class.getMethod("dockerClient");
        assertThat(method.getReturnType())
                .as("dockerClient() must return DockerClient")
                .isEqualTo(DockerClient.class);
    }

    // --- Bean creation tests ---

    @Test
    void should_returnNonNullDockerClient_when_dockerClientIsCalled() {
        DockerClient client = config.dockerClient();
        assertThat(client).isNotNull();
    }

    @Test
    void should_returnDockerClientInstance_when_dockerClientIsCalled() {
        DockerClient client = config.dockerClient();
        assertThat(client).isInstanceOf(DockerClient.class);
    }

    @Test
    void should_returnNewInstancePerCall_when_dockerClientIsCalledMultipleTimes() {
        DockerClient client1 = config.dockerClient();
        DockerClient client2 = config.dockerClient();
        assertThat(client1).isNotSameAs(client2);
    }

    // --- Different host configurations ---

    @Test
    void should_createDockerClient_when_unixSocketHostIsUsed() {
        DockerClient client = config.dockerClient();
        assertThat(client).isNotNull();
    }

    @Test
    void should_createDockerClient_when_tcpHostIsUsed() throws Exception {
        setDockerHost("tcp://localhost:2375");
        DockerClient client = config.dockerClient();
        assertThat(client).isNotNull();
    }
}
