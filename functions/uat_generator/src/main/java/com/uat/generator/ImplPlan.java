package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A delivery plan derived from the spec without an LLM: used when the plan call
 * fails, runs out of time, or in mock mode. Groups items into the same phases the
 * plan prompt asks for, with rough per-item efforts.
 */
public final class ImplPlan {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplPlan() {}

    public static List<ObjectNode> derive(ObjectNode spec) {
        List<ObjectNode> tasks = new ArrayList<>();
        String prev = null;

        List<JsonNode> blocking = new ArrayList<>();
        for (JsonNode q : spec.path("open_questions")) if (q.path("blocking").asBoolean(false)) blocking.add(q);
        if (!blocking.isEmpty()) {
            prev = add(tasks, "1. Clarify", "Resolve blocking open questions with the client",
                    "Get answers for: " + ids(blocking) + ".", "Consultant", 1.0 * blocking.size(), "P0",
                    blocking, null);
        }

        List<JsonNode> newModules = included(spec, "modules", "api");
        if (!newModules.isEmpty()) {
            prev = add(tasks, "2. Data model", "Create custom modules",
                    "Create " + names(newModules, "plural_label") + ".", "Admin", 1.0 * newModules.size(), "P0",
                    newModules, prev);
        }
        Map<String, List<JsonNode>> fieldsByModule = new LinkedHashMap<>();
        for (JsonNode f : included(spec, "fields", null)) {
            fieldsByModule.computeIfAbsent(f.path("module").asText(), k -> new ArrayList<>()).add(f);
        }
        String afterModules = prev;
        String lastData = prev;
        for (Map.Entry<String, List<JsonNode>> e : fieldsByModule.entrySet()) {
            lastData = add(tasks, "2. Data model", "Create " + e.getValue().size() + " field(s) on " + e.getKey(),
                    names(e.getValue(), "label") + ".", "Admin", Math.max(1, 0.25 * e.getValue().size()), "P0",
                    e.getValue(), afterModules);
        }
        List<JsonNode> layouts = included(spec, "layouts", null);
        if (!layouts.isEmpty()) {
            lastData = add(tasks, "2. Data model", "Configure layouts", names(layouts, "name") + ".", "Admin",
                    1.5 * layouts.size(), "P1", layouts, lastData);
        }
        List<JsonNode> rules = included(spec, "validation_rules", null);
        if (!rules.isEmpty()) {
            lastData = add(tasks, "2. Data model", "Add validation rules", names(rules, "name") + ".", "Admin",
                    0.5 * rules.size(), "P1", rules, lastData);
        }
        List<JsonNode> roles = included(spec, "roles", null);
        if (!roles.isEmpty()) {
            add(tasks, "3. Access", "Set up roles and permissions", names(roles, "name") + ".", "Admin",
                    0.5 * roles.size() + 1, "P1", roles, null);
        }
        List<JsonNode> flows = included(spec, "workflows", null);
        String lastAuto = lastData;
        for (JsonNode w : flows) {
            String type = w.path("type").asText("workflow");
            double h = "blueprint".equals(type) ? 6 : "approval".equals(type) ? 3 : 1.5;
            lastAuto = add(tasks, "4. Automation", "Build " + type + ": " + w.path("name").asText(),
                    w.path("trigger").asText("") + (w.path("criteria").asText("").isEmpty() ? "" : " — " + w.path("criteria").asText()),
                    "Consultant", h, "P0", List.of(w), lastData);
        }
        for (JsonNode f : included(spec, "functions", null)) {
            add(tasks, "5. Custom code", "Write and test function " + f.path("name").asText(),
                    f.path("purpose").asText(""), "Developer", 4, "P1", List.of(f), lastData);
        }
        if (!tasks.isEmpty()) {
            String verify = add(tasks, "6. Verify & UAT", "Verify org against spec and run UAT",
                    "Run /implement/verify, then generate and execute UAT cases from the approved spec.",
                    "QA", 4, "P0", new ArrayList<>(), lastAuto);
            add(tasks, "7. Go-live", "Go-live and hand-over",
                    "Deploy from sandbox, user training, hypercare.", "Consultant", 4, "P1", new ArrayList<>(), verify);
        }
        return tasks;
    }

    /** Short "what" text for an item, used in the plan prompt and task descriptions. */
    public static String describe(String section, JsonNode o) {
        switch (section) {
            case "modules":          return "module " + o.path("plural_label").asText() + " (" + o.path("kind").asText() + ")";
            case "fields":           return "field " + o.path("module").asText() + "." + o.path("label").asText()
                                            + " (" + o.path("data_type").asText() + ")";
            case "layouts":          return "layout " + o.path("module").asText() + " / " + o.path("name").asText();
            case "validation_rules": return "validation rule " + o.path("module").asText() + ": " + o.path("name").asText();
            case "workflows":        return o.path("type").asText() + " " + o.path("module").asText() + ": " + o.path("name").asText();
            case "roles":            return "role " + o.path("name").asText();
            case "functions":        return "Deluge function " + o.path("name").asText();
            case "open_questions":   return "open question" + (o.path("blocking").asBoolean() ? " (blocking)" : "")
                                            + ": " + o.path("question").asText();
            default:                 return o.path("title").asText();
        }
    }

    private static String add(List<ObjectNode> tasks, String phase, String title, String description,
                              String owner, double hours, String priority, List<JsonNode> items, String dependsOn) {
        ObjectNode t = MAPPER.createObjectNode();
        t.put("phase", phase);
        t.put("title", title);
        t.put("description", description);
        t.put("owner_role", owner);
        t.put("effort_hours", Math.round(hours * 2) / 2.0);
        t.put("priority", priority);
        ArrayNode dep = t.putArray("depends_on");
        if (dependsOn != null) dep.add(dependsOn);
        ArrayNode refs = t.putArray("spec_refs");
        Set<String> req = new LinkedHashSet<>();
        for (JsonNode i : items) {
            refs.add(i.path("id").asText());
            i.path("req_ids").forEach(r -> req.add(r.asText()));
        }
        ArrayNode reqIds = t.putArray("req_ids");
        req.forEach(reqIds::add);
        tasks.add(t);
        return title;
    }

    private static List<JsonNode> included(ObjectNode spec, String section, String executor) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode o : spec.path(section)) {
            if (!o.path("include").asBoolean(true)) continue;
            if (executor != null && !executor.equals(o.path("executor").asText())) continue;
            out.add(o);
        }
        return out;
    }

    private static String ids(List<JsonNode> items) {
        List<String> s = new ArrayList<>();
        for (JsonNode i : items) s.add(i.path("id").asText());
        return String.join(", ", s);
    }

    private static String names(List<JsonNode> items, String field) {
        List<String> s = new ArrayList<>();
        for (JsonNode i : items) {
            s.add(i.path(field).asText());
            if (s.size() == 8) { s.add("and " + (items.size() - 8) + " more"); break; }
        }
        return String.join(", ", s);
    }
}
