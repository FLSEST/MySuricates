package com.automation.service.ai.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogTest {

    @Test
    void available_copiesTheModelList() {
        List<ModelCatalog.ModelOption> source = new ArrayList<>(List.of(ModelCatalog.ModelOption.of("opus", "Opus")));
        Instant at = Instant.parse("2026-09-12T19:44:12Z");

        ModelCatalog catalog = ModelCatalog.available("CLAUDE", "static-aliases", at, source);
        source.add(ModelCatalog.ModelOption.of("haiku", "Haiku"));

        assertThat(catalog.available()).isTrue();
        assertThat(catalog.reason()).isNull();
        assertThat(catalog.fetchedAt()).isEqualTo(at);
        assertThat(catalog.models()).extracting(ModelCatalog.ModelOption::value).containsExactly("opus");
        assertThatThrownBy(() -> catalog.models().add(ModelCatalog.ModelOption.of("x", "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unavailable_hasReasonNoModelsAndCurrentTimestamp() {
        Instant before = Instant.now();

        ModelCatalog catalog = ModelCatalog.unavailable("PI", "pi-cli", "pi not installed");

        assertThat(catalog.available()).isFalse();
        assertThat(catalog.reason()).isEqualTo("pi not installed");
        assertThat(catalog.models()).isEmpty();
        assertThat(catalog.fetchedAt()).isBetween(before, Instant.now());
    }

    @Test
    void modelOptionOf_leavesOptionalMetadataUnset() {
        assertThat(ModelCatalog.ModelOption.of("sonnet", "Sonnet"))
                .isEqualTo(new ModelCatalog.ModelOption("sonnet", "Sonnet", null, null, null));
    }

    @Test
    void jsonShape_isWhatTheFrontendReads() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ModelCatalog catalog = ModelCatalog.available("PI", "pi-cli", Instant.parse("2026-09-12T00:00:00Z"),
                List.of(new ModelCatalog.ModelOption("anthropic/claude-opus-5", "anthropic · claude-opus-5",
                        null, 1_000_000, true)));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(catalog));

        assertThat(json.get("providerId").asText()).isEqualTo("PI");
        assertThat(json.get("available").asBoolean()).isTrue();
        assertThat(json.get("fetchedAt").asText()).isEqualTo("2026-09-12T00:00:00Z");
        JsonNode model = json.get("models").get(0);
        assertThat(model.get("value").asText()).isEqualTo("anthropic/claude-opus-5");
        assertThat(model.get("contextWindow").asInt()).isEqualTo(1_000_000);
        assertThat(model.get("supportsThinking").asBoolean()).isTrue();

        assertThat(mapper.readValue(mapper.writeValueAsString(catalog), ModelCatalog.class)).isEqualTo(catalog);
    }
}
