package com.automation.service.workflow;

import com.automation.executor.ExecutionContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redirect absolute file inputs into the snapshot without changing their trust classification. */
final class WorkspaceInputPaths {
    private WorkspaceInputPaths() {}

    static void remap(ExecutionContext context, Map<String, Object> manifest) {
        String source = String.valueOf(manifest.get("sourceRepository"));
        String workspace = String.valueOf(manifest.get("workspacePath"));
        Pattern prefix = Pattern.compile(Pattern.quote(source) + "(?=$|[/\\s\"'])");
        new HashMap<>(context.getVariables()).forEach((name, value) -> {
            if (name.equals("project_path") || name.startsWith("workspace_")
                    || name.equals("execution_path") || name.startsWith("execution_path_")) return;
            // Value-only write: the setters would re-label the variable, and the trust classes are
            // three (trusted / external untrusted / platform data), not two.
            context.getVariables().put(name, remapValue(value, prefix, workspace));
        });
    }

    private static Object remapValue(Object value, Pattern source, String workspace) {
        if (value instanceof String text) return source.matcher(text).replaceAll(Matcher.quoteReplacement(workspace));
        if (value instanceof List<?> list) return list.stream().map(item -> remapValue(item, source, workspace)).toList();
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new HashMap<>();
            map.forEach((key, item) -> result.put(key, remapValue(item, source, workspace)));
            return result;
        }
        return value;
    }
}
