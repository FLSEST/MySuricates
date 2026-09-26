package com.automation.service.assistant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift gate for the config key table in {@code docs/datenmodell.md} and its English twin (TODO
 * 64c638bb): every key the table names for a block type has to be read where that type runs
 * ({@link ExecutorConfigReads}), and every block type an executor supports has its row. The table
 * was almost entirely wrong ({@code prompt}, {@code thenPrompt}, {@code workflowId}, {@code skillId}
 * …) until 4d1f7068 rewrote it — without a gate it drifts again.
 *
 * <p><b>The table</b>: rows in the section {@code ## BlockDefinition} whose first cell is a
 * backticked word. <b>Named keys</b>: in the second cell, everything before the first {@code ;} is
 * the key list (after it a free note, such as where the capability's skill goes). The list is split
 * at commas outside parentheses, and each item starts with its backticked key; parentheses behind it
 * hold defaults or values ({@code (`list`/`command`)}), not keys. An item without a leading backticked
 * key is a problem of its own — this gate could not see it.
 */
class DataModelConfigKeysGateTest {

    /** Relative to the backend module, like the other gates that read the repository (../workflow-pool). */
    private static final List<Path> DOCS = List.of(
            Path.of("../docs/datenmodell.md"), Path.of("../docs/en/datenmodell.md"));

    private static final Pattern SECTION = Pattern.compile("^## BlockDefinition\\b.*");
    private static final Pattern NEXT_SECTION = Pattern.compile("^#{1,2} .*");
    private static final Pattern ROW = Pattern.compile("^\\|\\s*`([^`]+)`\\s*\\|(.*)\\|\\s*$");
    private static final Pattern LEADING_KEY = Pattern.compile("^`([A-Za-z][A-Za-z0-9_]*)`");

    @Test
    void everyKeyTheDataModelTableNamesIsReadWhereTheBlockTypeRuns() throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> types = ExecutorConfigReads.types();
        for (Path doc : DOCS) {
            Map<String, String> rows = configRows(Files.readAllLines(doc), doc.toString(), problems);
            Set<String> withoutRow = new TreeSet<>(types);
            withoutRow.removeAll(rows.keySet());
            if (!withoutRow.isEmpty()) {
                problems.add(doc + ": the config table has no row for " + withoutRow);
            }
            for (Map.Entry<String, String> row : rows.entrySet()) {
                String where = doc + " row '" + row.getKey() + "'";
                if (!types.contains(row.getKey())) {
                    problems.add(where + ": no executor supports this block type");
                    continue;
                }
                ExecutorConfigReads.reportUnread(row.getKey(), where, keys(row.getValue(), where, problems), problems);
            }
        }
        assertThat(problems).as(BlockSpecConfigKeysGateTest.HINT).isEmpty();
    }

    /**
     * Counter-probe: keys that stood in the table before 4d1f7068 are reported. Not {@code workflowId}:
     * the sub-workflow executor still reads it as a legacy spelling of {@code workflow_id}.
     */
    @Test
    void theOldDriftIsReported() throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, String> rows = configRows(List.of(
                "## BlockDefinition (JSON-Struktur)",
                "| Typ | Wichtigste Config-Felder |",
                "|---|---|",
                "| `simple` | `prompt` |",
                "| `conditional` | `condition`, `thenPrompt` |",
                "| `skill-capability` | `skillId`, `capability` |"), "sample", problems);
        rows.forEach((type, cell) -> {
            try {
                ExecutorConfigReads.reportUnread(type, "sample row '" + type + "'", keys(cell, type, problems), problems);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(problems).hasSize(3)
                .anySatisfy(p -> assertThat(p).contains("'simple'").contains("`prompt`"))
                .anySatisfy(p -> assertThat(p).contains("'conditional'").contains("`thenPrompt`"))
                .anySatisfy(p -> assertThat(p).contains("'skill-capability'").contains("`skillId`"));
    }

    @Test
    void keys_takeTheLeadingKeyOfEachItemAndSkipValuesAndTheNote() {
        List<String> problems = new ArrayList<>();
        Set<String> keys = keys("`command`, `items_source` (`list`/`command`), `cases` (Liste mit `value`, `blocks`),"
                + " `max_iterations` (100), `allow_writes`; der Skill steht in `skillIds` am Block", "sample", problems);

        assertThat(problems).isEmpty();
        assertThat(keys).containsExactly("command", "items_source", "cases", "max_iterations", "allow_writes");
    }

    @Test
    void keys_reportAnItemWithoutBacktickedKey() {
        List<String> problems = new ArrayList<>();
        Set<String> keys = keys("`command`, timeout_seconds", "sample", problems);

        assertThat(keys).containsExactly("command");
        assertThat(problems).singleElement().asString().contains("sample").contains("timeout_seconds");
    }

    @Test
    void configRows_onlyReadTheBlockDefinitionSection() {
        List<String> problems = new ArrayList<>();
        Map<String, String> rows = configRows(List.of(
                "## Tabellen",
                "| `id` | TEXT | PK |",
                "## BlockDefinition (JSON Structure)",
                "| Field | Type | Description |",
                "| `simple` | `command` |",
                "## Flyway Migrations",
                "| `V1` | `V1__x.sql` |"), "sample", problems);

        assertThat(problems).isEmpty();
        assertThat(rows).containsOnlyKeys("simple");
    }

    @Test
    void configRows_reportAMissingTable() {
        List<String> problems = new ArrayList<>();
        configRows(List.of("# Datenmodell", "## Enums"), "sample", problems);

        assertThat(problems).singleElement().asString().contains("sample").contains("no config table");
    }

    // ---- parsing -----------------------------------------------------------------------------------

    /** Block type → key cell of the rows in the {@code ## BlockDefinition} section. */
    static Map<String, String> configRows(List<String> lines, String where, List<String> problems) {
        Map<String, String> rows = new LinkedHashMap<>();
        boolean inSection = false;
        for (String line : lines) {
            if (SECTION.matcher(line).matches()) {
                inSection = true;
                continue;
            }
            if (inSection && NEXT_SECTION.matcher(line).matches()) {
                break;
            }
            Matcher row = inSection ? ROW.matcher(line) : null;
            if (row == null || !row.matches()) {
                continue;
            }
            // The field table above the config table (`type`, `name`, `config`, …) has three cells.
            String cells = row.group(2);
            if (cells.contains("|")) {
                continue;
            }
            if (rows.put(row.group(1), cells.strip()) != null) {
                problems.add(where + ": two rows for '" + row.group(1) + "'");
            }
        }
        if (rows.isEmpty()) {
            problems.add(where + ": found no config table in the '## BlockDefinition' section — this gate would"
                    + " measure nothing");
        }
        return rows;
    }

    /** The keys a key cell names: the leading backticked key of each item of the key list. */
    static Set<String> keys(String cell, String where, List<String> problems) {
        String list = cell.contains(";") ? cell.substring(0, cell.indexOf(';')) : cell;
        Set<String> keys = new LinkedHashSet<>();
        for (String item : splitOutsideParentheses(list)) {
            Matcher key = LEADING_KEY.matcher(item.strip());
            if (key.find()) {
                keys.add(key.group(1));
            } else if (!item.isBlank()) {
                problems.add(where + ": item '" + item.strip() + "' does not start with a backticked key"
                        + " — invisible to this gate");
            }
        }
        return keys;
    }

    private static List<String> splitOutsideParentheses(String text) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                items.add(text.substring(start, i));
                start = i + 1;
            }
        }
        items.add(text.substring(start));
        return items;
    }
}
