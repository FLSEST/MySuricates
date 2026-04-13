package com.automation.mapper;

import com.automation.dto.response.ContainerResponse;
import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerMapperTest {

    private ContainerMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ContainerMapperImpl();
    }

    @Test
    void mapsAllFields() {
        Instant start = Instant.now();
        Instant stop = start.plusSeconds(60);
        Container container = Container.builder()
                .id("c-1")
                .dockerContainerId("docker-abc123")
                .imageName("my-image:latest")
                .status(ContainerStatus.RUNNING)
                .startedAt(start)
                .stoppedAt(stop)
                .build();

        ContainerResponse response = mapper.toResponse(container);

        assertThat(response.id()).isEqualTo("c-1");
        assertThat(response.dockerContainerId()).isEqualTo("docker-abc123");
        assertThat(response.imageName()).isEqualTo("my-image:latest");
        assertThat(response.status()).isEqualTo(ContainerStatus.RUNNING);
        assertThat(response.startedAt()).isEqualTo(start);
        assertThat(response.stoppedAt()).isEqualTo(stop);
    }

    @Test
    void nullOptionalFields_mappedAsNull() {
        Container container = Container.builder()
                .id("c-2")
                .dockerContainerId("docker-xyz")
                .imageName("image:1.0")
                .status(ContainerStatus.STOPPED)
                .build();

        ContainerResponse response = mapper.toResponse(container);

        assertThat(response.startedAt()).isNull();
        assertThat(response.stoppedAt()).isNull();
    }

    @Test
    void nullContainer_returnsNull() {
        ContainerResponse response = mapper.toResponse(null);

        assertThat(response).isNull();
    }
}
