package com.automation.service.pool;

import com.automation.dto.request.ExportToPoolRequest;
import com.automation.dto.response.ExportToPoolResponse;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PoolExportWriterTest {

    private static final String UUID_A = "11111111-2222-3333-4444-555555555555";

    private final ObjectMapper mapper = new ObjectMapper();
    private final PoolExportWriter writer = new PoolExportWriter(mapper);

    @TempDir
    Path tmp;

    private static BlockDefinition block(String type, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(type);
        b.setConfig(new LinkedHashMap<>(config));
        return b;
    }

    private static Workflow.WorkflowBuilder base() {
        return Workflow.builder().id("wf-1").name("Täglicher Bericht!")
                .blocks(List.of(block("shell", Map.of("command", "echo hi"))));
    }

    private Map<String, Object> read(Path file) throws IOException {
        return mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    @Test
    void ohneRequestGeltenWorkflowWerteUndDefaults() throws IOException {
        Path poolDir = tmp.resolve("neu/pool");

        ExportToPoolResponse res = writer.write(base().build(), null, PoolScope.PORTABLE, poolDir);

        assertThat(res.filename()).isEqualTo("t-glicher-bericht.json");
        assertThat(res.poolId()).isEqualTo("t-glicher-bericht");
        assertThat(res.sanitizedPaths()).isEmpty();
        Map<String, Object> json = read(poolDir.resolve(res.filename()));
        assertThat(json.keySet()).containsExactly(
                "name", "icon", "category", "scope", "blocks", "version", "author");
        assertThat(json.get("name")).isEqualTo(Map.of("de", "Täglicher Bericht!", "en", "Täglicher Bericht!"));
        assertThat(json).containsEntry("icon", "🔧")
                .containsEntry("category", "CUSTOM")
                .containsEntry("scope", "portable")
                .containsEntry("version", "1.0.0")
                .containsEntry("author", "MySuricates");
    }

    @Test
    void requestWerteUndOptionaleFelderInKanonischerReihenfolge() throws IOException {
        Workflow wf = base().description("Beschreibung").icon("📊").instructions("Sei knapp.")
                .model("opus").effort("high").version("2.1.0")
                .defaultContext(Map.of("days", 7))
                .metadata(new LinkedHashMap<>(Map.of("factoryGenerated", true, "origin", "hand")))
                .build();
        ExportToPoolRequest req = new ExportToPoolRequest("Daily Report", "Tagesbericht", "Desc EN", null,
                "REPORTING", List.of("report"), "Flo");

        ExportToPoolResponse res = writer.write(wf, req, PoolScope.REPO, tmp);

        assertThat(res.filename()).isEqualTo("daily-report.json");
        Map<String, Object> json = read(tmp.resolve("daily-report.json"));
        assertThat(json.keySet()).containsExactly("name", "description", "icon", "category", "scope", "tags",
                "blocks", "instructions", "model", "effort", "version", "author", "defaultContext", "metadata");
        assertThat(json.get("name")).isEqualTo(Map.of("de", "Tagesbericht", "en", "Daily Report"));
        assertThat(json.get("description")).isEqualTo(Map.of("de", "Beschreibung", "en", "Desc EN"));
        assertThat(json).containsEntry("category", "REPORTING").containsEntry("scope", "repo")
                .containsEntry("tags", List.of("report")).containsEntry("author", "Flo")
                .containsEntry("version", "2.1.0");
        assertThat(json.get("metadata")).isEqualTo(Map.of("origin", "hand"));
    }

    @Test
    void kategorieAusFabrikMetadatenUndReineFabrikMetadatenEntfallen() throws IOException {
        Workflow wf = base().instructions("  ").effort(" ")
                .metadata(new LinkedHashMap<>(Map.of("factoryCategory", "DEVOPS", "factoryTopic", "x")))
                .defaultContext(Map.of())
                .build();

        ExportToPoolResponse res = writer.write(wf, null, PoolScope.PORTABLE, tmp);

        Map<String, Object> json = read(tmp.resolve(res.filename()));
        assertThat(json).containsEntry("category", "DEVOPS")
                .doesNotContainKeys("metadata", "instructions", "effort", "defaultContext", "description", "tags");
    }

    @Test
    void bestehendeDateiWirdNichtUeberschriebenSondernDurchnummeriert() throws IOException {
        Files.writeString(tmp.resolve("daily.json"), "alt");
        Files.writeString(tmp.resolve("daily-2.json"), "alt");
        ExportToPoolRequest req = new ExportToPoolRequest("Daily", null, null, null, null, null, null);

        ExportToPoolResponse res = writer.write(base().build(), req, PoolScope.PORTABLE, tmp);

        assertThat(res.filename()).isEqualTo("daily-3.json");
        assertThat(res.poolId()).isEqualTo("daily-3");
        assertThat(Files.readString(tmp.resolve("daily.json"))).isEqualTo("alt");
    }

    @Test
    void nameOhneSlugZeichenBekommtZufallsSlug() throws IOException {
        ExportToPoolResponse res = writer.write(base().name("???").build(), null, PoolScope.PORTABLE, tmp);

        assertThat(res.filename()).matches("workflow-[0-9a-f]{8}\\.json");
    }

    @Test
    void instanzBindungenWerdenEntferntUndGemeldet() throws IOException {
        Workflow wf = base().blocks(List.of(block("sub-workflow", Map.of("workflowId", UUID_A, "keep", "ja"))))
                .build();

        ExportToPoolResponse res = writer.write(wf, null, PoolScope.PORTABLE, tmp);

        assertThat(res.sanitizedPaths()).anyMatch(p -> p.contains("workflowId"));
        String content = Files.readString(tmp.resolve(res.filename()));
        assertThat(content).doesNotContain(UUID_A).contains("\"keep\" : \"ja\"");
    }
}
