package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundlePath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Import side of the path tokens: every {@code @@path:N@@} of the bundle needs a target folder;
 * the tokens in the content are replaced by it ({@code @@path:1@@/tests} → {@code /new/root/tests}).
 * {@code {{ project_path }}} is no token and stays a placeholder.
 */
final class BundlePathMapping {

    private BundlePathMapping() {
    }

    /** @throws IllegalArgumentException (400) listing every token without a (non-blank) target */
    static ProjectBundle apply(ProjectBundle bundle, Map<String, String> mappings) {
        List<String> unresolved = new ArrayList<>();
        Map<String, String> targets = new HashMap<>();
        for (BundlePath p : bundle.paths()) {
            String target = mappings.get(p.token());
            if (target == null || target.isBlank()) {
                unresolved.add(p.token() + " (" + p.original() + ")");
            } else {
                targets.put(p.token(), normalize(target));
            }
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalArgumentException("Missing path mapping for: " + String.join(", ", unresolved));
        }
        if (targets.isEmpty()) {
            return bundle;
        }
        return BundleContent.rewrite(bundle, (value, owner, field) -> {
            if (!value.contains("@@path:")) {
                return value;
            }
            Matcher m = BundleValidator.PATH_TOKEN.matcher(value);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(targets.getOrDefault(m.group(), m.group())));
            }
            m.appendTail(out);
            return out.toString();
        });
    }

    /** Without trailing slash — the sub path after a token starts with its own {@code /}. */
    private static String normalize(String target) {
        String t = target.strip();
        while (t.length() > 1 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
