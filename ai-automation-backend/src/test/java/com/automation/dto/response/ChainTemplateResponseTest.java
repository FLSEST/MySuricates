package com.automation.dto.response;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChainTemplateResponseTest {

    private ChainTemplateResponse.EntryInfo sampleEntry() {
        return new ChainTemplateResponse.EntryInfo(
                "tpl-1",
                Map.of("de", "Eintrag", "en", "Entry")
        );
    }

    private ChainTemplateResponse sample() {
        return new ChainTemplateResponse(
                "chain-1",
                Map.of("de", "Mein Chain", "en", "My Chain"),
                Map.of("de", "Beschreibung", "en", "Description"),
                "icon-name",
                List.of(sampleEntry())
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_via_accessors() {
            ChainTemplateResponse response = sample();

            assertThat(response.id()).isEqualTo("chain-1");
            assertThat(response.name()).containsEntry("de", "Mein Chain").containsEntry("en", "My Chain");
            assertThat(response.description()).containsEntry("de", "Beschreibung").containsEntry("en", "Description");
            assertThat(response.icon()).isEqualTo("icon-name");
            assertThat(response.entries()).hasSize(1);
            assertThat(response.entries().get(0).templateId()).isEqualTo("tpl-1");
        }

        @Test
        void should_allow_null_values_in_all_fields() {
            ChainTemplateResponse response = new ChainTemplateResponse(null, null, null, null, null);

            assertThat(response.id()).isNull();
            assertThat(response.name()).isNull();
            assertThat(response.description()).isNull();
            assertThat(response.icon()).isNull();
            assertThat(response.entries()).isNull();
        }

        @Test
        void should_allow_empty_entries_list() {
            ChainTemplateResponse response = new ChainTemplateResponse(
                    "id", Map.of(), Map.of(), "icon", List.of()
            );

            assertThat(response.entries()).isEmpty();
        }
    }

    @Nested
    class EntryInfoRecord {

        @Test
        void should_expose_entry_fields() {
            ChainTemplateResponse.EntryInfo entry = sampleEntry();

            assertThat(entry.templateId()).isEqualTo("tpl-1");
            assertThat(entry.label()).containsEntry("de", "Eintrag").containsEntry("en", "Entry");
        }

        @Test
        void should_allow_null_in_entry_fields() {
            ChainTemplateResponse.EntryInfo entry = new ChainTemplateResponse.EntryInfo(null, null);

            assertThat(entry.templateId()).isNull();
            assertThat(entry.label()).isNull();
        }

        @Test
        void should_be_equal_when_fields_match() {
            ChainTemplateResponse.EntryInfo a = sampleEntry();
            ChainTemplateResponse.EntryInfo b = sampleEntry();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_template_id_differs() {
            ChainTemplateResponse.EntryInfo a = sampleEntry();
            ChainTemplateResponse.EntryInfo b = new ChainTemplateResponse.EntryInfo(
                    "other-tpl", Map.of("de", "Eintrag", "en", "Entry")
            );

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_include_fields_in_to_string() {
            ChainTemplateResponse.EntryInfo entry = sampleEntry();

            assertThat(entry.toString())
                    .contains("tpl-1")
                    .contains("Eintrag");
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_be_equal_when_all_fields_match() {
            ChainTemplateResponse a = sample();
            ChainTemplateResponse b = sample();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_id_differs() {
            ChainTemplateResponse a = sample();
            ChainTemplateResponse b = new ChainTemplateResponse(
                    "other-id",
                    Map.of("de", "Mein Chain", "en", "My Chain"),
                    Map.of("de", "Beschreibung", "en", "Description"),
                    "icon-name",
                    List.of(sampleEntry())
            );

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_not_be_equal_when_entries_differ() {
            ChainTemplateResponse a = sample();
            ChainTemplateResponse b = new ChainTemplateResponse(
                    "chain-1",
                    Map.of("de", "Mein Chain", "en", "My Chain"),
                    Map.of("de", "Beschreibung", "en", "Description"),
                    "icon-name",
                    List.of()
            );

            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    class ToString {

        @Test
        void should_include_id_and_icon_in_to_string() {
            String text = sample().toString();

            assertThat(text)
                    .contains("chain-1")
                    .contains("icon-name");
        }
    }
}
