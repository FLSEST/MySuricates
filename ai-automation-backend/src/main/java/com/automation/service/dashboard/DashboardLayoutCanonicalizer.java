package com.automation.service.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static com.automation.service.dashboard.DashboardLayoutRules.MAX_CONFIG_KEYS;
import static com.automation.service.dashboard.DashboardLayoutRules.MAX_CONFIG_STRING;
import static com.automation.service.dashboard.DashboardLayoutRules.MAX_DAYS;
import static com.automation.service.dashboard.DashboardLayoutRules.MAX_NAME_LENGTH;
import static com.automation.service.dashboard.DashboardLayoutRules.MAX_VIEWS;
import static com.automation.service.dashboard.DashboardLayoutRules.MAX_WIDGETS_PER_VIEW;
import static com.automation.service.dashboard.DashboardLayoutRules.echo;
import static com.automation.service.dashboard.DashboardLayoutRules.invalid;
import static com.automation.service.dashboard.DashboardLayoutRules.requireObject;
import static com.automation.service.dashboard.DashboardLayoutRules.requireOnlyFields;
import static com.automation.service.dashboard.DashboardLayoutRules.requiredId;

/**
 * Checks the layout of a snapshot and rebuilds it as canonical JSON (fixed key order, defaults
 * omitted), so equal layouts serialize — and hash — equally. A private mapper is used on purpose so
 * Spring's Jackson customisation can never change a hash.
 */
final class DashboardLayoutCanonicalizer {

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private static final Pattern CONFIG_KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]{0,39}$");
    private static final Set<String> SIZES = Set.of("stat", "chart", "third", "twoThirds", "full");
    private static final Set<String> LAYOUT_FIELDS = Set.of("activeViewId", "views");
    private static final Set<String> VIEW_FIELDS = Set.of("id", "name", "widgets", "filter");
    private static final Set<String> WIDGET_FIELDS = Set.of("instanceId", "type", "hidden", "size", "config");
    private static final Set<String> FILTER_FIELDS = Set.of("projectId", "days");

    private DashboardLayoutCanonicalizer() {
    }

    /** The canonical layout; throws IllegalArgumentException for anything outside the format. */
    static ObjectNode canonicalLayout(JsonNode layout) {
        requireObject(layout, "layout");
        requireOnlyFields(layout, LAYOUT_FIELDS, "layout");
        JsonNode views = layout.get("views");
        if (views == null || !views.isArray() || views.isEmpty() || views.size() > MAX_VIEWS) {
            throw invalid("layout.views must be an array with 1 to " + MAX_VIEWS + " views");
        }
        String activeViewId = requiredId(layout.get("activeViewId"), "layout.activeViewId");
        ObjectNode out = CANONICAL.createObjectNode();
        out.put("activeViewId", activeViewId);
        ArrayNode outViews = out.putArray("views");
        Set<String> viewIds = new HashSet<>();
        for (int i = 0; i < views.size(); i++) {
            ObjectNode view = canonicalView(views.get(i), "layout.views[" + i + "]");
            if (!viewIds.add(view.get("id").textValue())) {
                throw invalid("layout.views[" + i + "].id is duplicate");
            }
            outViews.add(view);
        }
        if (!viewIds.contains(activeViewId)) {
            throw invalid("layout.activeViewId must reference a view");
        }
        return out;
    }

    static String write(JsonNode node) {
        try {
            return CANONICAL.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode canonicalView(JsonNode view, String path) {
        requireObject(view, path);
        requireOnlyFields(view, VIEW_FIELDS, path);
        ObjectNode out = CANONICAL.createObjectNode();
        out.put("id", requiredId(view.get("id"), path + ".id"));
        JsonNode name = view.get("name");
        // Code points, not UTF-16 units: the frontend cuts view names by code points too.
        if (name == null || !name.isTextual()
                || name.textValue().codePointCount(0, name.textValue().length()) > MAX_NAME_LENGTH) {
            throw invalid(path + ".name must be a string of at most " + MAX_NAME_LENGTH + " characters");
        }
        out.put("name", name.textValue());
        JsonNode filter = view.get("filter");
        if (filter != null && !filter.isNull()) {
            ObjectNode outFilter = canonicalFilter(filter, path + ".filter");
            if (!outFilter.isEmpty()) {
                out.set("filter", outFilter);
            }
        }
        JsonNode widgets = view.get("widgets");
        if (widgets == null || !widgets.isArray() || widgets.size() > MAX_WIDGETS_PER_VIEW) {
            throw invalid(path + ".widgets must be an array with at most " + MAX_WIDGETS_PER_VIEW + " entries");
        }
        ArrayNode outWidgets = out.putArray("widgets");
        Set<String> instanceIds = new HashSet<>();
        for (int i = 0; i < widgets.size(); i++) {
            ObjectNode widget = canonicalWidget(widgets.get(i), path + ".widgets[" + i + "]");
            if (!instanceIds.add(widget.get("instanceId").textValue())) {
                throw invalid(path + ".widgets[" + i + "].instanceId is duplicate");
            }
            outWidgets.add(widget);
        }
        return out;
    }

    private static ObjectNode canonicalFilter(JsonNode filter, String path) {
        requireObject(filter, path);
        requireOnlyFields(filter, FILTER_FIELDS, path);
        ObjectNode out = CANONICAL.createObjectNode();
        JsonNode projectId = filter.get("projectId");
        if (projectId != null && !projectId.isNull()) {
            out.put("projectId", requiredId(projectId, path + ".projectId"));
        }
        JsonNode days = filter.get("days");
        if (days != null && !days.isNull()) {
            if (!days.isInt() || days.intValue() < 1 || days.intValue() > MAX_DAYS) {
                throw invalid(path + ".days must be an integer from 1 to " + MAX_DAYS);
            }
            out.put("days", days.intValue());
        }
        return out;
    }

    private static ObjectNode canonicalWidget(JsonNode widget, String path) {
        requireObject(widget, path);
        requireOnlyFields(widget, WIDGET_FIELDS, path);
        ObjectNode out = CANONICAL.createObjectNode();
        out.put("instanceId", requiredId(widget.get("instanceId"), path + ".instanceId"));
        out.put("type", requiredId(widget.get("type"), path + ".type"));
        JsonNode hidden = widget.get("hidden");
        if (hidden != null && !hidden.isNull()) {
            if (!hidden.isBoolean()) {
                throw invalid(path + ".hidden must be a boolean");
            }
            if (hidden.booleanValue()) {
                out.put("hidden", true);
            }
        }
        JsonNode size = widget.get("size");
        if (size != null && !size.isNull()) {
            if (!size.isTextual() || !SIZES.contains(size.textValue())) {
                throw invalid(path + ".size must be one of " + SIZES);
            }
            out.put("size", size.textValue());
        }
        JsonNode config = widget.get("config");
        if (config != null && !config.isNull()) {
            ObjectNode outConfig = canonicalConfig(config, path + ".config");
            if (!outConfig.isEmpty()) {
                out.set("config", outConfig);
            }
        }
        return out;
    }

    private static ObjectNode canonicalConfig(JsonNode config, String path) {
        requireObject(config, path);
        if (config.size() > MAX_CONFIG_KEYS) {
            throw invalid(path + " must have at most " + MAX_CONFIG_KEYS + " keys");
        }
        Map<String, JsonNode> sorted = new TreeMap<>();
        config.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
        ObjectNode out = CANONICAL.createObjectNode();
        sorted.forEach((key, value) -> {
            if (!CONFIG_KEY.matcher(key).matches()) {
                throw invalid(path + " has an invalid key: " + echo(key));
            }
            if (value.isTextual() && value.textValue().length() <= MAX_CONFIG_STRING) {
                out.put(key, value.textValue());
            } else if (value.isBoolean()) {
                out.put(key, value.booleanValue());
            } else if (value.isNumber() && Double.isFinite(value.doubleValue())) {
                // Rebuilt through our own factory rather than copying the parsed node: an integral
                // double (3.0) must canonicalize identically to an int (3) so equal layouts hash equally.
                double d = value.doubleValue();
                out.set(key, d == Math.rint(d) && Math.abs(d) < 1e15
                        ? CANONICAL.getNodeFactory().numberNode((long) d)
                        : CANONICAL.getNodeFactory().numberNode(d));
            } else {
                throw invalid(path + "." + echo(key) + " must be a string (at most " + MAX_CONFIG_STRING
                        + " characters), a finite number or a boolean");
            }
        });
        return out;
    }
}
