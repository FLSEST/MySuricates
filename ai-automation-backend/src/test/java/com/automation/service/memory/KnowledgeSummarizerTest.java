package com.automation.service.memory;

import com.automation.service.memory.KnowledgeSummarizer.SummarizationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSummarizerTest {

    @Test
    void should_store_all_fields_when_summarization_result_constructed() {
        SummarizationResult result = new SummarizationResult(
                "Test Title",
                "Test Content",
                List.of("tag1", "tag2"));

        assertThat(result.title()).isEqualTo("Test Title");
        assertThat(result.content()).isEqualTo("Test Content");
        assertThat(result.tags()).containsExactly("tag1", "tag2");
    }

    @Test
    void should_allow_empty_tags_list_when_no_tags_provided() {
        SummarizationResult result = new SummarizationResult(
                "Title",
                "Content",
                List.of());

        assertThat(result.title()).isEqualTo("Title");
        assertThat(result.content()).isEqualTo("Content");
        assertThat(result.tags()).isEmpty();
    }

    @Test
    void should_allow_null_values_when_record_constructed_with_nulls() {
        SummarizationResult result = new SummarizationResult(null, null, null);

        assertThat(result.title()).isNull();
        assertThat(result.content()).isNull();
        assertThat(result.tags()).isNull();
    }

    @Test
    void should_be_equal_when_two_records_have_same_values() {
        SummarizationResult a = new SummarizationResult("T", "C", List.of("x"));
        SummarizationResult b = new SummarizationResult("T", "C", List.of("x"));

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void should_not_be_equal_when_records_differ_in_any_field() {
        SummarizationResult base = new SummarizationResult("T", "C", List.of("x"));

        assertThat(base).isNotEqualTo(new SummarizationResult("X", "C", List.of("x")));
        assertThat(base).isNotEqualTo(new SummarizationResult("T", "Y", List.of("x")));
        assertThat(base).isNotEqualTo(new SummarizationResult("T", "C", List.of("y")));
    }

    @Test
    void should_include_field_values_when_to_string_called() {
        SummarizationResult result = new SummarizationResult(
                "MyTitle",
                "MyContent",
                List.of("alpha"));

        String str = result.toString();

        assertThat(str).contains("MyTitle");
        assertThat(str).contains("MyContent");
        assertThat(str).contains("alpha");
    }

    @Test
    void parse_fullFormat_readsTitleTagsAndContent() {
        String response = """
                TITLE: Spring Boot API Conventions
                TAGS: spring, api, testing, docker
                ---
                ## Project conventions
                - Build: `mvn clean package`

                ## Successful strategies
                - Always run tests before commit
                """;

        SummarizationResult result = SummarizationResult.parse(response);

        assertThat(result.title()).isEqualTo("Spring Boot API Conventions");
        assertThat(result.tags()).containsExactly("spring", "api", "testing", "docker");
        assertThat(result.content()).startsWith("## Project conventions").endsWith("- Always run tests before commit");
    }

    @Test
    void parse_missingFormat_fallsBackToDefaultTitleNoTagsAndWholeResponse() {
        String response = "Just some plain text without the expected format.";

        SummarizationResult result = SummarizationResult.parse(response);

        assertThat(result.title()).isEqualTo("Workflow Knowledge");
        assertThat(result.tags()).isEmpty();
        assertThat(result.content()).isEqualTo(response);
    }

    @Test
    void parse_longTitle_isCutAt60Characters() {
        SummarizationResult result = SummarizationResult.parse("TITLE: " + "A".repeat(80) + "\nTAGS: a\n---\ncontent");

        assertThat(result.title()).isEqualTo("A".repeat(60));
        assertThat(result.content()).isEqualTo("content");
    }

    @Test
    void parse_emptyTagEntries_areDropped() {
        SummarizationResult result = SummarizationResult.parse("TITLE: T\nTAGS: a, , b,\n---\nbody");

        assertThat(result.tags()).containsExactly("a", "b");
    }

    @Test
    void parse_withoutSeparator_keepsTitleButNoTagsAndWholeResponse() {
        String response = "TITLE: Only title\nTAGS: a, b\nno separator here";

        SummarizationResult result = SummarizationResult.parse(response);

        assertThat(result.title()).isEqualTo("Only title");
        assertThat(result.tags()).isEmpty();
        assertThat(result.content()).isEqualTo(response);
    }
}
