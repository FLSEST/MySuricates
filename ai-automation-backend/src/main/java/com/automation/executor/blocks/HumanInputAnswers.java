package com.automation.executor.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What an AI actor has to deliver for a {@code human-input} block (TODO dca0c03e): the task text that
 * describes the input or form, and the check that turns its answer into what a person's answer would be —
 * a single value in the field's format, or a JSON object with one value per form field. The checks mirror
 * the dialog ({@code HumanInputDialog}: required fields, select options) plus the field formats the block
 * documents (number, checkbox, date, {@code validation_pattern}); an answer that fails them fails the step.
 */
final class HumanInputAnswers {

    private HumanInputAnswers() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** The normalized answer, or the reason it is unusable ({@code error} set, {@code answer} null). */
    record Validated(String answer, String error) {
        static Validated ok(String answer) { return new Validated(answer, null); }
        static Validated bad(String error) { return new Validated(null, error); }
        boolean valid() { return error == null; }
    }

    /** The task of the AI actor: the resolved prompt and the exact shape of the answer. */
    static String task(String prompt, HumanInputBlockExecutor.Settings settings) {
        StringBuilder task = new StringBuilder("## Input requested\n\n").append(prompt).append("\n\n");
        if (settings.multiField()) {
            task.append("## Form fields\n\n");
            for (Map<String, Object> field : settings.fields()) {
                String name = string(field.get("variable_name"));
                if (name == null || name.isBlank()) continue;
                task.append("- `").append(name).append("`");
                String label = string(field.get("label"));
                if (label != null && !label.isBlank()) task.append(" — ").append(label);
                task.append(describe(string(field.get("input_type")), options(field.get("options")),
                        !Boolean.FALSE.equals(field.get("required")), string(field.get("validation_pattern"))));
                String description = string(field.get("description"));
                if (description != null && !description.isBlank()) task.append("; ").append(description);
                task.append('\n');
            }
            task.append("\nReply with one JSON object only — the field names as keys, every value a string, "
                    + "no other text and no code fence.");
        } else {
            task.append("## Answer format\n\nOne value")
                    .append(describe(settings.inputType(), settings.options(), settings.required(),
                            settings.validationPattern()))
                    .append(".\n\nReply with the value only, no explanation.");
        }
        return task.toString();
    }

    /** Checks the AI's answer the way the dialog would and returns it in the shape a person's answer has. */
    static Validated validate(String text, HumanInputBlockExecutor.Settings settings) {
        String answer = text == null ? "" : text.strip();
        if (!settings.multiField()) {
            return validateValue(answer, settings.inputType(), settings.options(), settings.required(),
                    settings.validationPattern(), null);
        }
        Map<String, Object> parsed = parseObject(answer);
        if (parsed == null) {
            return Validated.bad("the form answer is not a JSON object");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map<String, Object> field : settings.fields()) {
            String name = string(field.get("variable_name"));
            if (name == null || name.isBlank()) continue;
            Object raw = parsed.get(name);
            String value = raw == null ? "" : String.valueOf(raw).strip();
            Validated one = validateValue(value, string(field.get("input_type")), options(field.get("options")),
                    !Boolean.FALSE.equals(field.get("required")), string(field.get("validation_pattern")), name);
            if (!one.valid()) return one;
            values.put(name, one.answer());
        }
        try {
            return Validated.ok(JSON.writeValueAsString(values));
        } catch (Exception e) {
            return Validated.bad("the form answer could not be serialized: " + e.getMessage());
        }
    }

    /**
     * Whether a person's answer equals the AI's proposal. The proposal is first normalized the way an AI
     * actor's answer would be ({@link #validate}: checkbox {@code yes} becomes {@code true}, a select takes the
     * option's spelling); a proposal that fails the checks never agrees. Forms compare field by field; a
     * select ignores case like the options do, everything else compares the trimmed text.
     */
    static boolean same(String human, String proposal, HumanInputBlockExecutor.Settings settings) {
        if (human == null || proposal == null) return false;
        Validated normalized = validate(proposal, settings);
        if (!normalized.valid()) return false;
        if (settings.multiField()) {
            Map<String, Object> a = parseObject(human);
            Map<String, Object> b = parseObject(normalized.answer());
            if (a == null || b == null) return false;
            for (Map<String, Object> field : settings.fields()) {
                String name = string(field.get("variable_name"));
                if (name == null || name.isBlank()) continue;
                if (!sameValue(text(a.get(name)), text(b.get(name)), string(field.get("input_type")))) return false;
            }
            return true;
        }
        return sameValue(human.strip(), normalized.answer(), settings.inputType());
    }

    private static boolean sameValue(String human, String proposal, String inputType) {
        return "select".equals(inputType) || "checkbox".equals(inputType)
                ? human.equalsIgnoreCase(proposal)
                : human.equals(proposal);
    }

    private static Validated validateValue(String value, String inputType, List<String> options, boolean required,
                                           String pattern, String fieldName) {
        String where = fieldName == null ? "" : "field '" + fieldName + "': ";
        String type = inputType == null ? "text" : inputType;
        if (value.isBlank()) {
            return required ? Validated.bad(where + "a value is required") : Validated.ok("");
        }
        switch (type) {
            case "select" -> {
                String picked = options.stream().filter(o -> o.strip().equalsIgnoreCase(value)).findFirst().orElse(null);
                if (picked == null) {
                    return Validated.bad(where + "'" + value + "' is none of the options " + options);
                }
                return Validated.ok(picked);
            }
            case "checkbox" -> {
                return switch (value.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "ja" -> Validated.ok("true");
                    case "false", "no", "nein" -> Validated.ok("false");
                    default -> Validated.bad(where + "'" + value + "' is neither true nor false");
                };
            }
            case "number" -> {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    return Validated.bad(where + "'" + value + "' is not a number");
                }
            }
            case "date" -> {
                if (!DATE.matcher(value).matches()) {
                    return Validated.bad(where + "'" + value + "' is not a date in the form YYYY-MM-DD");
                }
            }
            default -> { }
        }
        if (pattern != null && !pattern.isBlank()) {
            try {
                if (!Pattern.compile(pattern).matcher(value).find()) {
                    return Validated.bad(where + "'" + value + "' does not match the pattern " + pattern);
                }
            } catch (PatternSyntaxException e) {
                return Validated.bad(where + "validation_pattern is not a valid regular expression: " + e.getDescription());
            }
        }
        return Validated.ok(value);
    }

    private static String describe(String inputType, List<String> options, boolean required, String pattern) {
        String type = inputType == null ? "text" : inputType;
        StringBuilder out = new StringBuilder(" (").append(switch (type) {
            case "select" -> "one of: " + String.join(", ", options);
            case "checkbox" -> "true or false";
            case "number" -> "a number";
            case "date" -> "a date as YYYY-MM-DD";
            case "textarea" -> "free text, several lines allowed";
            default -> "free text";
        });
        if (!required) out.append("; optional, empty allowed");
        if (pattern != null && !pattern.isBlank()) out.append("; must match the regular expression ").append(pattern);
        return out.append(')').toString();
    }

    /** The first JSON object in the text — also when the model wraps it in a code fence despite the instruction. */
    private static Map<String, Object> parseObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return JSON.readValue(text.substring(start, end + 1), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> options(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(o -> { if (o != null) out.add(String.valueOf(o)); });
        }
        return out;
    }

    private static String string(Object raw) {
        return raw instanceof String s ? s : null;
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).strip();
    }
}
