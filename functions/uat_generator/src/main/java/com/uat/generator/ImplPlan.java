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

    /**
     * The execution plan LiftOff itself follows: one step per build lane in run order, with what Verify
     * reads back and the UAT that closes it. Derived from the spec and the capability lanes — no agent call.
     */
    public static List<ObjectNode> derive(ObjectNode spec) {
        List<ObjectNode> tasks = new ArrayList<>();
        String prev = null;

        List<JsonNode> blocking = new ArrayList<>();
        for (JsonNode q : spec.path("open_questions")) if (q.path("blocking").asBoolean(false) && q.path("include").asBoolean(true)) blocking.add(q);
        if (!blocking.isEmpty()) {
            prev = add(tasks, "1. Clarify", "Answer " + blocking.size() + " blocking question(s)",
                    "Build is held until these are answered in the Blueprint: " + ids(blocking) + ".", "You", "review",
                    0.25 * blocking.size(), "P0", blocking, null, "");
        }

        // ---- 2. API lane, in the order Build applies them ----
        String lastApi = prev;
        List<JsonNode> newModules = included(spec, "modules", "api");
        if (!newModules.isEmpty()) {
            lastApi = add(tasks, "2. Build via API", "Create " + newModules.size() + " module(s)",
                    names(newModules, "plural_label") + ".", "LiftOff", "api", 0.05 * newModules.size(), "P0",
                    newModules, prev, "each module is listed by GET settings/modules");
        }
        List<JsonNode> sets = included(spec, "global_picklists", "api");
        if (!sets.isEmpty()) {
            lastApi = add(tasks, "2. Build via API", "Create " + sets.size() + " shared option set(s)",
                    names(sets, "name") + ".", "LiftOff", "api", 0.05 * sets.size(), "P0", sets, prev,
                    "each set is listed by GET settings/global_picklists");
        }
        Map<String, List<JsonNode>> fieldsByModule = new LinkedHashMap<>();
        for (JsonNode f : included(spec, "fields", "api")) {
            fieldsByModule.computeIfAbsent(f.path("module").asText(), k -> new ArrayList<>()).add(f);
        }
        String afterModules = lastApi;
        for (Map.Entry<String, List<JsonNode>> e : fieldsByModule.entrySet()) {
            lastApi = add(tasks, "2. Build via API", "Create " + e.getValue().size() + " field(s) on " + e.getKey(),
                    names(e.getValue(), "label") + ".", "LiftOff", "api", 0.05 * e.getValue().size(), "P0",
                    e.getValue(), afterModules, "each field exists with the specified type, length and options");
        }
        for (JsonNode d : included(spec, "dependencies", "api")) {
            lastApi = add(tasks, "2. Build via API", "Map " + d.path("child").asText() + " options to " + d.path("parent").asText() + " on " + d.path("module").asText(),
                    d.path("mapping").size() + " parent option(s) mapped.", "LiftOff", "api", 0.05, "P1", List.of(d), lastApi,
                    "dependency listed by GET settings/layouts/{id}/map_dependency");
        }
        List<JsonNode> roles = included(spec, "roles", "api");
        roles.addAll(included(spec, "roles", "partial"));
        if (!roles.isEmpty()) {
            lastApi = add(tasks, "2. Build via API", "Create " + roles.size() + " role(s)", names(roles, "name") + ".",
                    "LiftOff", "api", 0.05 * roles.size(), "P1", roles, prev, "roles listed by GET settings/roles");
        }
        List<JsonNode> apiRules = included(spec, "workflows", "api");
        apiRules.addAll(included(spec, "workflows", "partial"));
        for (JsonNode w : apiRules) {
            if ("pipeline".equals(w.path("type").asText())) {
                lastApi = add(tasks, "2. Build via API", "Create pipeline: " + w.path("name").asText(),
                        "Stages " + String.join(" → ", ImplBlueprint.states(w)) + ".", "LiftOff", "api", 0.1, "P1", List.of(w), lastApi,
                        "pipeline listed by GET settings/pipeline for the Deals layout");
                continue;
            }
            if ("blueprint".equals(w.path("type").asText())) {
                lastApi = add(tasks, "2. Build via API", "Create Blueprint: " + w.path("name").asText(),
                        "States " + String.join(" → ", ImplBlueprint.states(w)) + "; created in Draft and activated.", "LiftOff", "api", 0.2, "P0", List.of(w), lastApi,
                        "blueprint listed by GET settings/blueprints, published");
                continue;
            }
            lastApi = add(tasks, "2. Build via API", "Create rule: " + w.path("name").asText(),
                    ruleText(w), "LiftOff", "api", 0.1, "P0", List.of(w), lastApi,
                    "rule exists on " + w.path("module").asText() + " with its actions");
        }

        for (JsonNode fn : included(spec, "functions", "api")) {
            lastApi = add(tasks, "2. Build via API", "Create function: " + fn.path("name").asText(),
                    (fn.path("purpose").asText("Deluge function") + "."), "LiftOff", "api", 0.1, "P1", List.of(fn), lastApi,
                    "function listed by GET settings/functions, state active");
        }

        // ---- 3. Setup lane: what the runner walks you through ----
        String lastUi = lastApi;
        List<JsonNode> ui = new ArrayList<>();
        for (String sct : new String[]{"global_picklists", "fields", "layouts", "dependencies", "validation_rules", "workflows", "functions"}) ui.addAll(included(spec, sct, "rpa"));
        for (JsonNode w : included(spec, "workflows", "partial")) {
            if (w.path("other_actions").size() > 0) ui.add(w);
        }
        for (JsonNode item : ui) {
            String what = describeAny(spec, item);
            lastUi = add(tasks, "3. In Setup (guided)", what, "No create API — the guided runner opens Setup and walks the steps; tick it when done.",
                    "You", "ui", 0.25, "P1", List.of(item), lastApi, "confirmed by you (read back where the API allows)");
        }

        // ---- 4 & 5. Verify and UAT close the loop ----
        if (!tasks.isEmpty() || hasAny(spec)) {
            List<JsonNode> all = new ArrayList<>();
            for (String sct : new String[]{"modules", "fields", "roles", "workflows", "layouts", "validation_rules"}) all.addAll(included(spec, sct, null));
            String verify = add(tasks, "4. Verify", "Read the org back", "Every built item is read through the API and compared with the blueprint.",
                    "LiftOff", "verify", 0.05, "P0", all, lastUi, "every item reports match");
            add(tasks, "5. UAT", "Run the acceptance tests", "Test records are created, each acceptance criterion is checked, then the records are deleted. Sign-off unlocks when all pass.",
                    "LiftOff", "uat", 0.1, "P0", new ArrayList<>(), verify, "all acceptance tests pass");
        }
        return tasks;
    }

    private static boolean hasAny(ObjectNode spec) {
        for (String s : ImplSpec.SECTIONS) if (!"plan".equals(s) && !"open_questions".equals(s) && spec.path(s).size() > 0) return true;
        return false;
    }

    private static String ruleText(JsonNode w) {
        StringBuilder sb = new StringBuilder();
        String trig = w.path("trigger_type").asText("");
        sb.append(trig.isEmpty() ? w.path("trigger").asText("") : w.path("date_trigger").isObject() ? ImplSpec.dateTriggerText(w.path("date_trigger")) : "on " + trig.replace('_', ' '));
        if (w.path("conditions").size() > 0) {
            List<String> cs = new ArrayList<>();
            for (JsonNode c : w.path("conditions")) cs.add(c.path("field").asText() + " " + c.path("comparator").asText().replace('_', ' ') + (c.path("value").asText("").isEmpty() ? "" : " " + c.path("value").asText()));
            sb.append(" when ").append(String.join(" " + w.path("match").asText("and") + " ", cs));
        }
        List<String> acts = new ArrayList<>();
        for (JsonNode f : w.path("field_updates")) acts.add("set " + f.path("field").asText() + " = " + f.path("value").asText());
        for (JsonNode t : w.path("tasks")) acts.add("task \"" + t.path("subject").asText() + "\"");
        for (JsonNode a : w.path("other_actions")) acts.add(a.asText());
        if (!acts.isEmpty()) sb.append(" → ").append(String.join(", ", acts));
        return sb.toString();
    }

    private static String describeAny(ObjectNode spec, JsonNode item) {
        for (String s : ImplSpec.SECTIONS) {
            for (JsonNode o : spec.path(s)) if (o == item) return describe(s, item);
        }
        return item.path("name").asText(item.path("label").asText(""));
    }

    /** Short "what" text for an item, used in the plan prompt and task descriptions. */
    public static String describe(String section, JsonNode o) {
        switch (section) {
            case "modules":          return "module " + o.path("plural_label").asText() + " (" + o.path("kind").asText() + ")";
            case "fields":           return "field " + o.path("module").asText() + "." + o.path("label").asText()
                                            + " (" + o.path("data_type").asText() + ")";
            case "layouts":          return "layout " + o.path("module").asText() + " / " + o.path("name").asText();
            case "global_picklists": return "shared option set " + o.path("name").asText();
            case "dependencies":     return "picklist dependency " + o.path("parent").asText() + " → " + o.path("child").asText() + " on " + o.path("module").asText();
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
                              String owner, String lane, double hours, String priority, List<JsonNode> items,
                              String dependsOn, String verify) {
        ObjectNode t = MAPPER.createObjectNode();
        t.put("phase", phase);
        t.put("title", title);
        t.put("description", description);
        t.put("owner_role", owner);
        t.put("lane", lane);
        t.put("verify", verify == null ? "" : verify);
        t.put("effort_hours", Math.round(hours * 20) / 20.0);
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

    /** "a, b, c" from a JSON string array. */
    static String join(JsonNode arr) {
        List<String> s = new ArrayList<>();
        for (JsonNode v : arr) s.add(v.asText());
        return String.join(", ", s);
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
