package com.automation.service.workflow.analyzer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BaseUrlVariablesTest {

    @Nested
    class FirstAssignment {

        @Test
        void should_findAShellAssignmentOfTheApiBase() {
            assertThat(BaseUrlVariables.firstAssignment("API=http://127.0.0.1:9090/api/v1; curl $API/todos"))
                    .isEqualTo("/api/v1");
        }

        @Test
        void should_findExportedAndQuotedAssignments_withTrailingSlashCutOff() {
            assertThat(BaseUrlVariables.firstAssignment("export BASE=\"http://localhost:9090/api/v1/\""))
                    .isEqualTo("/api/v1");
        }

        @Test
        void should_findActuatorPathsAndPythonAssignments() {
            assertThat(BaseUrlVariables.firstAssignment("HOST=http://localhost:9090/actuator/metrics"))
                    .isEqualTo("/actuator/metrics");
            assertThat(BaseUrlVariables.firstAssignment("base = 'http://127.0.0.1:9090/api/v1/todos'"))
                    .isEqualTo("/api/v1/todos");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "API=http://0.0.0.0:9090/api/v1",
                "API=http://[::1]:9090/api/v1",
                "API=http://host.docker.internal:9090/api/v1",
                "API={{ base_url }}/api/v1",
                "API=$HOST/api/v1",
                "API=${HOST}/api/v1",
                "API=localhost:9090/api/v1"})
        void should_recogniseEveryLocalHostForm(String code) {
            assertThat(BaseUrlVariables.firstAssignment(code)).isEqualTo("/api/v1");
        }

        @Test
        void should_skipKeyFreeProbes_andReportTheNextAssignment() {
            String code = "H=http://localhost:9090/api/v1/system/health\nW=http://localhost:9090/api/v1/workflows";

            assertThat(BaseUrlVariables.firstAssignment(code)).isEqualTo("/api/v1/workflows");
            assertThat(BaseUrlVariables.firstAssignment("H=http://localhost:9090/actuator/health")).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "API=https://api.example.com/api/v1",
                "HOST=http://localhost:9090",
                "curl http://localhost:9090/api/v1/todos",
                "echo nothing here"})
        void should_ignoreForeignHostsBareBasesAndDirectCalls(String code) {
            assertThat(BaseUrlVariables.firstAssignment(code)).isNull();
        }
    }

    @Nested
    class FirstLaterCall {

        @Test
        void should_findABareBaseCalledWithAnApiEndpoint() {
            assertThat(BaseUrlVariables.firstLaterCall("HOST=http://localhost:9090; curl \"$HOST/api/v1/todos\""))
                    .isEqualTo("/api/v1/todos");
        }

        @Test
        void should_findABracedReferenceToAnActuatorEndpoint() {
            assertThat(BaseUrlVariables.firstLaterCall("HOST=http://localhost:9090\ncurl ${HOST}/actuator/metrics"))
                    .isEqualTo("/actuator/metrics");
        }

        @Test
        void should_prefixApi_when_theBaseAlreadyEndsInApi() {
            assertThat(BaseUrlVariables.firstLaterCall("API=http://localhost:9090/api; curl $API/v1/todos"))
                    .isEqualTo("/api/v1/todos");
        }

        @Test
        void should_findPythonFStringReferences() {
            String code = "base = \"http://localhost:9090\"\nrequests.get(f\"{base}/api/v1/workflows\")";

            assertThat(BaseUrlVariables.firstLaterCall(code)).isEqualTo("/api/v1/workflows");
        }

        @Test
        void should_cutOffTrailingDotsAndSlashes() {
            assertThat(BaseUrlVariables.firstLaterCall("HOST=http://localhost:9090; curl $HOST/api/v1/todos/."))
                    .isEqualTo("/api/v1/todos");
        }

        @Test
        void should_allowKeyFreeProbes_butReportALaterRealEndpoint() {
            assertThat(BaseUrlVariables.firstLaterCall("HOST=http://localhost:9090; curl $HOST/actuator/health"))
                    .isNull();
            assertThat(BaseUrlVariables.firstLaterCall(
                    "HOST=http://localhost:9090; curl $HOST/api/v1/system/info; curl $HOST/api/v1/todos"))
                    .isEqualTo("/api/v1/todos");
        }

        @Test
        void should_needTheV1Suffix_when_baseEndsInApi() {
            assertThat(BaseUrlVariables.firstLaterCall("API=http://localhost:9090/api; curl $API/todos")).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "HOST=http://localhost:9090",                                  // assignment alone
                "HOST=http://localhost:5173; curl $HOST/index.html",           // local service without API path
                "HOST=https://example.com; curl $HOST/api/v1/todos",           // foreign host
                "HOST=http://localhost:9090; curl $OTHER/api/v1/todos",        // other variable
                "API=http://localhost:9090/api/v1; curl $API/todos"})          // full base: firstAssignment's case
        void should_reportNothing_withoutALocalBaseCalledWithAnEndpoint(String code) {
            assertThat(BaseUrlVariables.firstLaterCall(code)).isNull();
        }
    }
}
