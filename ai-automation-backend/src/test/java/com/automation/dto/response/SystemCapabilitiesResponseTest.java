package com.automation.dto.response;

import com.automation.dto.response.SystemCapabilitiesResponse.BlockTypeCapabilityResponse;
import com.automation.dto.response.SystemCapabilitiesResponse.ModuleCapabilityResponse;
import com.automation.dto.response.SystemCapabilitiesResponse.RouteCapabilityResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemCapabilitiesResponseTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-07-03T10:00:00Z");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static RouteCapabilityResponse sampleRoute() {
        return new RouteCapabilityResponse(
                "/api/v1/workflows/{id}/execute",
                List.of("POST"),
                "WorkflowController",
                "executeWorkflow"
        );
    }

    private static BlockTypeCapabilityResponse sampleBlockType() {
        return new BlockTypeCapabilityResponse("shell", "Executes shell commands");
    }

    private static ModuleCapabilityResponse sampleModule() {
        return new ModuleCapabilityResponse("webhooks", "feature", false, "advanced", "stable",
                List.of("network"), List.of(), "automation.modules.webhooks", "AUTOMATION_MODULES_WEBHOOKS",
                "Inbound webhook triggers");
    }

    private static SystemCapabilitiesResponse sample() {
        return new SystemCapabilitiesResponse(
                "v1",
                "0.7.3",
                "release",
                "production",
                List.of(sampleRoute()),
                List.of(sampleBlockType()),
                "core",
                List.of(sampleModule()),
                GENERATED_AT
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_populate_all_fields_when_constructed() {
            SystemCapabilitiesResponse r = sample();

            assertThat(r.apiVersion()).isEqualTo("v1");
            assertThat(r.appVersion()).isEqualTo("0.7.3");
            assertThat(r.buildType()).isEqualTo("release");
            assertThat(r.environment()).isEqualTo("production");
            assertThat(r.routes()).containsExactly(sampleRoute());
            assertThat(r.blockTypes()).containsExactly(sampleBlockType());
            assertThat(r.preset()).isEqualTo("core");
            assertThat(r.modules()).containsExactly(sampleModule());
            assertThat(r.generatedAt()).isEqualTo(GENERATED_AT);
        }

        @Test
        void should_populate_nested_route_fields_when_constructed() {
            RouteCapabilityResponse route = sampleRoute();

            assertThat(route.path()).isEqualTo("/api/v1/workflows/{id}/execute");
            assertThat(route.methods()).containsExactly("POST");
            assertThat(route.controller()).isEqualTo("WorkflowController");
            assertThat(route.handler()).isEqualTo("executeWorkflow");
        }

        @Test
        void should_populate_nested_block_type_fields_when_constructed() {
            BlockTypeCapabilityResponse blockType = sampleBlockType();

            assertThat(blockType.type()).isEqualTo("shell");
            assertThat(blockType.summary()).isEqualTo("Executes shell commands");
        }

        @Test
        void should_accept_empty_capability_lists_when_constructed() {
            SystemCapabilitiesResponse r = new SystemCapabilitiesResponse(
                    "v1", "0.7.3", "release", "production",
                    List.of(), List.of(), "core", List.of(), GENERATED_AT
            );

            assertThat(r.routes()).isEmpty();
            assertThat(r.blockTypes()).isEmpty();
        }

        @Test
        void should_tolerate_null_values_when_constructed_with_nulls() {
            SystemCapabilitiesResponse r = new SystemCapabilitiesResponse(
                    null, null, null, null, null, null, null, null, null
            );

            assertThat(r.apiVersion()).isNull();
            assertThat(r.appVersion()).isNull();
            assertThat(r.buildType()).isNull();
            assertThat(r.environment()).isNull();
            assertThat(r.routes()).isNull();
            assertThat(r.blockTypes()).isNull();
            assertThat(r.generatedAt()).isNull();
        }
    }

    @Nested
    class EqualsHashCodeToString {

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sample()).isEqualTo(sample());
            assertThat(sample().hashCode()).isEqualTo(sample().hashCode());
        }

        @Test
        void should_not_be_equal_when_a_field_differs() {
            SystemCapabilitiesResponse other = new SystemCapabilitiesResponse(
                    "v2", "0.7.3", "release", "production",
                    List.of(sampleRoute()), List.of(sampleBlockType()), "core", List.of(sampleModule()), GENERATED_AT
            );

            assertThat(sample()).isNotEqualTo(other);
        }

        @Test
        void should_include_field_values_when_toString_called() {
            String s = sample().toString();

            assertThat(s).contains("v1");
            assertThat(s).contains("0.7.3");
            assertThat(s).contains("production");
            assertThat(s).contains("shell");
        }
    }

    @Nested
    class JacksonSerialization {

        @Test
        void should_serialize_all_fields_when_written_as_json() throws Exception {
            String json = MAPPER.writeValueAsString(sample());

            assertThat(json).contains("\"apiVersion\":\"v1\"");
            assertThat(json).contains("\"appVersion\":\"0.7.3\"");
            assertThat(json).contains("\"buildType\":\"release\"");
            assertThat(json).contains("\"environment\":\"production\"");
            assertThat(json).contains("\"path\":\"/api/v1/workflows/{id}/execute\"");
            assertThat(json).contains("\"methods\":[\"POST\"]");
            assertThat(json).contains("\"controller\":\"WorkflowController\"");
            assertThat(json).contains("\"handler\":\"executeWorkflow\"");
            assertThat(json).contains("\"type\":\"shell\"");
            assertThat(json).contains("\"summary\":\"Executes shell commands\"");
            assertThat(json).contains("\"preset\":\"core\"");
            assertThat(json).contains("\"name\":\"webhooks\"");
            assertThat(json).contains("\"envVar\":\"AUTOMATION_MODULES_WEBHOOKS\"");
            assertThat(json).contains("\"generatedAt\":\"2026-07-03T10:00:00Z\"");
        }

        @Test
        void should_serialize_empty_arrays_when_capability_lists_are_empty() throws Exception {
            SystemCapabilitiesResponse r = new SystemCapabilitiesResponse(
                    "v1", "0.7.3", "release", "production",
                    List.of(), List.of(), "core", List.of(), GENERATED_AT
            );

            String json = MAPPER.writeValueAsString(r);

            assertThat(json).contains("\"routes\":[]");
            assertThat(json).contains("\"blockTypes\":[]");
        }
    }
}
