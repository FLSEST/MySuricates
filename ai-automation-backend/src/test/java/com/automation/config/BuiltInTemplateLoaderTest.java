package com.automation.config;

import com.automation.util.LocalizedJson;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import com.automation.entity.BlockDefinition;
import com.automation.entity.TemplateKind;
import com.automation.entity.WorkflowTemplate;
import com.automation.executor.ModelUse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInTemplateLoaderTest {

    private static List<WorkflowTemplate> templates;

    @BeforeAll
    static void loadTemplates() {
        BuiltInTemplateLoader loader = new BuiltInTemplateLoader(new ObjectMapper());
        templates = loader.loadAll();
    }

    @Test
    void loadAll_returnsAllTemplates() {
        assertThat(templates).hasSizeGreaterThanOrEqualTo(44);
    }

    /** The loader skips a file it can't read with only a log line — here that has to fail the build. */
    @Test
    void loadAll_loadsEveryTemplateFile() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        int templateFiles = resolver.getResources("classpath:templates/builtin/*.json").length;
        int blueprintFiles = resolver.getResources("classpath:templates/blueprints/*.json").length;

        assertThat(templates.stream().filter(t -> t.getKind() == TemplateKind.TEMPLATE)).hasSize(templateFiles);
        assertThat(templates.stream().filter(t -> t.getKind() == TemplateKind.BLUEPRINT)).hasSize(blueprintFiles);
        assertThat(blueprintFiles).as("Blaupausen werden ausgeliefert").isPositive();
    }

    @Test
    void bilingualTemplate_keepsEnglishBlocks_andResolvesGermanFromItsSource() {
        WorkflowTemplate example = templates.stream().filter(t -> "blueprint-approval".equals(t.getId()))
                .findFirst().orElseThrow();
        assertThat(example.getKind()).isEqualTo(TemplateKind.BLUEPRINT);

        assertThat(example.getBlocks()).extracting(BlockDefinition::getName)
                .containsExactly("measure", "approval", "finish");
        assertThat(example.getBlocks().get(1).getConfig().get("options")).isEqualTo(List.of("Yes", "No"));
        assertThat(example.getI18nSource()).isNotBlank();

        List<BlockDefinition> german = LocalizedJson.blocksFor(example.getI18nSource(), "de", List.of());
        assertThat(german).extracting(BlockDefinition::getName).containsExactly("messen", "freigabe", "abschluss");
        assertThat(german.get(1).getConfig().get("question").toString()).contains("{{ block.messen.output }}");
        assertThat(german.get(1).getConfig().get("options")).isEqualTo(List.of("Ja", "Nein"));
    }

    /**
     * The picker's AI badge and its "runs without AI" filter read {@link ModelUse} (TODO 560d18dd);
     * it has to agree with {@code BlueprintGateTest}: only the AI group calls a model.
     */
    @Test
    void blueprints_callAModelOnlyInTheAiGroup_asThePickerShowsIt() {
        assertThat(templates.stream().filter(t -> t.getKind() == TemplateKind.BLUEPRINT))
                .allSatisfy(bp -> assertThat(ModelUse.needsAiAsBlueprint(bp.getBlocks())).as(bp.getId())
                        .isEqualTo("AI".equals(bp.getCategory())));
    }

    /**
     * Templates bilingual in their steps, each for a reason. {@code goal-guard} (TODO 259f4f66) is
     * installed by the platform itself (goal page switch) in the UI language, so its step names and
     * tile labels come in both languages; everything else ships single-language.
     */
    static final java.util.Set<String> BILINGUAL_TEMPLATES = java.util.Set.of("goal-guard");

    @Test
    void templatesAreSingleLanguage_blueprintsBilingual() {
        assertThat(templates.stream().filter(t -> t.getKind() == TemplateKind.TEMPLATE)
                        .filter(t -> !BILINGUAL_TEMPLATES.contains(t.getId())))
                .allSatisfy(t -> assertThat(t.getI18nSource()).as(t.getId()).isNull());
        assertThat(templates.stream().filter(t -> BILINGUAL_TEMPLATES.contains(t.getId())))
                .as("every listed bilingual template ships and carries its source")
                .hasSize(BILINGUAL_TEMPLATES.size())
                .allSatisfy(t -> assertThat(t.getI18nSource()).as(t.getId()).isNotBlank());
        assertThat(templates.stream().filter(t -> t.getKind() == TemplateKind.BLUEPRINT))
                .allSatisfy(t -> assertThat(t.getI18nSource()).as(t.getId()).isNotBlank());
    }

    @Test
    void allTemplates_haveRequiredFields() {
        for (WorkflowTemplate t : templates) {
            assertThat(t.getId()).as("id").isNotBlank();
            assertThat(t.getIcon()).as("icon for " + t.getId()).isNotBlank();
            assertThat(t.getName()).as("name for " + t.getId()).isNotNull();
            assertThat(t.getName().get("de")).as("name.de for " + t.getId()).isNotBlank();
            assertThat(t.getName().get("en")).as("name.en for " + t.getId()).isNotBlank();
            assertThat(t.getDescription()).as("description for " + t.getId()).isNotNull();
            assertThat(t.getDescription().get("de")).as("description.de for " + t.getId()).isNotBlank();
            assertThat(t.getDescription().get("en")).as("description.en for " + t.getId()).isNotBlank();
            assertThat(t.getCategory()).as("category for " + t.getId()).isNotBlank();
            assertThat(t.getBlocks()).as("blocks for " + t.getId()).isNotEmpty();
            assertThat(t.isBuiltIn()).as("builtIn for " + t.getId()).isTrue();
        }
    }

    /** The seeder removes built-in rows by file name ({@code shippedIds}), so a file's name is its id. */
    @Test
    void shippedIds_areTheFileNames_ofEveryLoadedTemplate() {
        Set<String> shipped = new BuiltInTemplateLoader(new ObjectMapper()).shippedIds();

        assertThat(shipped).containsExactlyInAnyOrderElementsOf(templates.stream().map(WorkflowTemplate::getId).toList());
    }

    @Test
    void allTemplates_haveUniqueIds() {
        Set<String> ids = new HashSet<>();
        for (WorkflowTemplate t : templates) {
            assertThat(ids.add(t.getId()))
                    .as("Duplicate ID: " + t.getId())
                    .isTrue();
        }
    }

    @Test
    void allTemplates_haveValidBlockTypes() {
        Set<String> validTypes = Set.of(
                "simple", "shell", "retry", "conditional", "foreach",
                "while", "switch", "agentic", "approval",
                "sub-workflow", "skill-capability", "human-input",
                "document", "integration", "todo-create", "todo-update", "todo-close",
                "compress", "compress-ai", "dashboard-report");

        for (WorkflowTemplate t : templates) {
            for (BlockDefinition block : t.getBlocks()) {
                assertThat(validTypes).as("block type '%s' in template '%s'", block.getType(), t.getId())
                        .contains(block.getType());
            }
        }
    }

    @Test
    void shellBlocks_neverInterpolatePlaceholdersInsideQuotedHeredocs() {
        // Security review 356c461e / P4: a {{ var }} inside a quoted heredoc is pasted into the
        // interpreter's source (e.g. as a Python string literal). Pass values on the shell line
        // with shell_escape_variables=true and read them from the environment instead.
        Pattern heredoc = Pattern.compile("<<'(\\w+)'\\n(.*?)\\n\\1(?:\\n|$)", Pattern.DOTALL);
        List<String> violations = new ArrayList<>();
        for (WorkflowTemplate t : templates) {
            for (BlockDefinition b : t.getBlocks()) {
                if (!"shell".equals(b.getType()) || b.getConfig() == null || b.getConfig().get("command") == null) continue;
                String cmd = b.getConfig().get("command").toString();
                Matcher m = heredoc.matcher(cmd);
                while (m.find()) {
                    if (m.group(2).contains("{{")) {
                        violations.add(t.getId() + "/" + b.getName() + ": placeholder inside heredoc");
                    }
                }
                if (cmd.contains("<<'") && cmd.contains("{{")
                        && !Boolean.TRUE.equals(b.getConfig().get("shell_escape_variables"))) {
                    violations.add(t.getId() + "/" + b.getName() + ": placeholder without shell_escape_variables");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void allTemplates_haveValidVersion() {
        for (WorkflowTemplate t : templates) {
            assertThat(t.getVersion()).as("version for " + t.getId())
                    .matches("\\d+\\.\\d+\\.\\d+");
        }
    }

    @Test
    void allTemplates_haveSequentialSortOrder() {
        for (TemplateKind kind : TemplateKind.values()) {
            assertSequentialSortOrder(templates.stream().filter(t -> t.getKind() == kind).toList());
        }
    }

    /** Templates and blueprints each count from 0 — they are shown in separate gallery tabs. */
    private static void assertSequentialSortOrder(List<WorkflowTemplate> ofOneKind) {
        List<Integer> sortOrders = ofOneKind.stream()
                .map(WorkflowTemplate::getSortOrder)
                .sorted()
                .toList();

        for (int i = 0; i < sortOrders.size(); i++) {
            assertThat(sortOrders.get(i)).as("sortOrder at position " + i).isEqualTo(i);
        }
    }
}
