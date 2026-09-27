package com.automation.dto.request;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExportToPoolRequestTest {

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_store_all_fields_when_constructed_with_values() {
            List<String> tags = List.of("automation", "build");
            ExportToPoolRequest request = new ExportToPoolRequest(
                    "Build Gate",
                    "Build-Gate",
                    "Verifies builds",
                    "Verifiziert Builds",
                    "ci",
                    tags,
                    "alex"
            );

            assertThat(request.nameEn()).isEqualTo("Build Gate");
            assertThat(request.nameDe()).isEqualTo("Build-Gate");
            assertThat(request.descriptionEn()).isEqualTo("Verifies builds");
            assertThat(request.descriptionDe()).isEqualTo("Verifiziert Builds");
            assertThat(request.category()).isEqualTo("ci");
            assertThat(request.tags()).containsExactly("automation", "build");
            assertThat(request.author()).isEqualTo("alex");
        }

        @Test
        void should_accept_null_values_for_all_fields() {
            ExportToPoolRequest request = new ExportToPoolRequest(
                    null, null, null, null, null, null, null
            );

            assertThat(request.nameEn()).isNull();
            assertThat(request.nameDe()).isNull();
            assertThat(request.descriptionEn()).isNull();
            assertThat(request.descriptionDe()).isNull();
            assertThat(request.category()).isNull();
            assertThat(request.tags()).isNull();
            assertThat(request.author()).isNull();
        }

        @Test
        void should_accept_empty_tags_list() {
            ExportToPoolRequest request = new ExportToPoolRequest(
                    "n", "n", "d", "d", "c", List.of(), "a"
            );

            assertThat(request.tags()).isNotNull().isEmpty();
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_be_equal_when_all_fields_match() {
            List<String> tags = List.of("a", "b");
            ExportToPoolRequest a = new ExportToPoolRequest(
                    "n1", "n2", "d1", "d2", "cat", tags, "auth"
            );
            ExportToPoolRequest b = new ExportToPoolRequest(
                    "n1", "n2", "d1", "d2", "cat", List.of("a", "b"), "auth"
            );

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_one_field_differs() {
            ExportToPoolRequest a = new ExportToPoolRequest(
                    "n1", "n2", "d1", "d2", "cat", List.of("x"), "auth"
            );
            ExportToPoolRequest b = new ExportToPoolRequest(
                    "DIFFERENT", "n2", "d1", "d2", "cat", List.of("x"), "auth"
            );

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_include_all_fields_in_toString() {
            ExportToPoolRequest request = new ExportToPoolRequest(
                    "EngName", "DeName", "EngDesc", "DeDesc", "tools", List.of("tag1"), "alice"
            );

            String result = request.toString();
            assertThat(result)
                    .contains("EngName")
                    .contains("DeName")
                    .contains("EngDesc")
                    .contains("DeDesc")
                    .contains("tools")
                    .contains("tag1")
                    .contains("alice");
        }
    }
}
