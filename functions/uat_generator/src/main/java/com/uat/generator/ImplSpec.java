package com.uat.generator;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The implementation spec: what has to be built in CRM to deliver a BRD.
 *
 * Sections (each an array of items):
 *   modules, fields, layouts, validation_rules, workflows, roles, functions,
 *   open_questions, plan
 *
 * Every item gets, server side:
 *   id        stable short id per section (MOD-1, FLD-3, ...)
 *   executor  who builds it: "api" (REST, /implement/apply), "partial" (REST creates it, a
 *             person finishes it), "rpa" (UI-only, for an RPA bot or a consultant),
 *             "review" (open questions), "projects" (plan), "none" (already there)
 *   status    new / exists / needs_update / conflict / not_compared (ImplCompare)
 *   include   true; the reviewer unticks items they don't want built
 *   req_ids   requirement IDs the item delivers
 *
 * This class parses LLM output into that shape, merges chunks and de-duplicates.
 */
public final class ImplSpec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

    public static final List<String> DATA_MODEL = Arrays.asList(
            "modules", "fields", "layouts", "validation_rules", "roles", "open_questions");
    public static final List<String> AUTOMATION = Arrays.asList(
            "workflows", "functions", "open_questions");
    public static final List<String> SECTIONS = Arrays.asList(
            "modules", "fields", "layouts", "validation_rules", "workflows", "roles",
            "functions", "open_questions", "plan");

    private static final Map<String, String> PREFIX = new LinkedHashMap<>();
    static {
        PREFIX.put("modules", "MOD");
        PREFIX.put("fields", "FLD");
        PREFIX.put("layouts", "LAY");
        PREFIX.put("validation_rules", "VAL");
        PREFIX.put("workflows", "WF");
        PREFIX.put("roles", "ROLE");
        PREFIX.put("functions", "FN");
        PREFIX.put("open_questions", "Q");
        PREFIX.put("plan", "TASK");
    }

    /** Field types the CRM field-create API accepts (decimal is sent as "double"). */
    public static final Set<String> API_FIELD_TYPES = new HashSet<>(Arrays.asList(
            "text", "textarea", "email", "phone", "website", "picklist", "multiselectpicklist",
            "date", "datetime", "integer", "bigint", "decimal", "currency", "percent",
            "boolean", "lookup"));
    private static final Set<String> KNOWN_FIELD_TYPES = new HashSet<>(Arrays.asList(
            "text", "textarea", "email", "phone", "website", "picklist", "multiselectpicklist",
            "date", "datetime", "integer", "bigint", "decimal", "currency", "percent",
            "boolean", "lookup", "multiselectlookup", "autonumber", "formula", "fileupload",
            "imageupload", "rollup_summary", "userlookup"));
    /** execute_when.type values the workflow-rules API is used for (others stay manual). */
    public static final Set<String> API_TRIGGERS = new HashSet<>(Arrays.asList(
            "create", "edit", "create_or_edit", "field_update", "delete"));
    private static final Set<String> TRIGGERS = new HashSet<>(Arrays.asList(
            "create", "edit", "create_or_edit", "field_update", "delete", "date_or_datetime"));
    private static final Set<String> COMPARATORS = new HashSet<>(Arrays.asList(
            "equal", "not_equal", "contains", "not_contains", "starts_with", "ends_with", "greater_than",
            "less_than", "greater_equal", "less_equal", "is_empty", "is_not_empty"));
    static final Set<String> DATE_PERIODS = new HashSet<>(Arrays.asList("b_days", "days", "weeks", "months", "years"));
    static final Set<String> RECUR = new HashSet<>(Arrays.asList("once", "monthly", "yearly"));
    static final Set<String> SCHEDULE_PERIODS = new HashSet<>(Arrays.asList("minutes", "hours", "business_hours", "days", "business_days", "weeks", "months", "years"));
    private static final Set<String> WORKFLOW_TYPES = new HashSet<>(Arrays.asList(
            "workflow", "blueprint", "assignment", "approval", "scoring", "escalation",
            "schedule", "cadence", "webhook", "sla", "macro", "view", "notification", "time_based", "pipeline"));

    private ImplSpec() {}

    /** "2 days before Follow_up_Date at 09:00" */
    static String dateTriggerText(JsonNode d) {
        int off = d.path("offset").asInt(0);
        String period = d.path("period").asText("days").replace("b_days", "business days");
        String when = off == 0 ? "On " : Math.abs(off) + " " + period + (off < 0 ? " before " : " after ");
        return when + d.path("field").asText() + " at " + d.path("time").asText("09:00") + ("once".equals(d.path("recur").asText("once")) ? "" : ", " + d.path("recur").asText());
    }

    /** field_updates / tasks / webhooks of a rule or of one scheduled block, normalised onto {@code o}. */
    static void actionLists(JsonNode in, ObjectNode o) {
        ArrayNode fus = o.putArray("field_updates");
        for (JsonNode f : in.path("field_updates")) {
            String field = text(f, "field");
            if (field.isEmpty()) continue;
            ObjectNode fu = fus.addObject().put("field", field);
            if (f.path("value").isArray()) fu.set("value", f.path("value"));
            else fu.put("value", text(f, "value"));
        }
        ArrayNode tasks = o.putArray("tasks");
        for (JsonNode t : in.path("tasks")) {
            String subject = text(t, "subject");
            if (subject.isEmpty()) continue;
            ObjectNode to = tasks.addObject().put("subject", subject)
                    .put("due_in_days", Math.max(0, t.path("due_in_days").asInt(0)));
            if (!text(t, "priority").isEmpty()) to.put("priority", text(t, "priority"));
        }
        ArrayNode hooks = o.putArray("webhooks");
        for (JsonNode h : in.path("webhooks")) {
            String url = text(h, "url");
            if (url.isEmpty()) continue;
            ObjectNode ho = hooks.addObject().put("url", url).put("http_method", text(h, "http_method").isEmpty() ? "POST" : text(h, "http_method").toUpperCase(Locale.ROOT));
            putIfText(ho, "name", text(h, "name"));
            putIfText(ho, "description", text(h, "description"));
        }
    }

    /** Result of parsing one LLM response. */
    public static final class Parsed {
        public final Map<String, List<ObjectNode>> items = new LinkedHashMap<>();
        public int dropped;
        public boolean salvaged;
        public String summary = "";
        public String error;
        public boolean ok() { return error == null; }
        public int size() {
            int n = 0;
            for (List<ObjectNode> l : items.values()) n += l.size();
            return n;
        }
    }

    /** Parses {"modules":[...], "fields":[...], ...}; keeps complete items from cut-off output. */
    public static Parsed parse(String raw, List<String> sections) {
        Parsed p = new Parsed();
        for (String s : sections) p.items.put(s, new ArrayList<>());
        String text = CaseParser.sliceToJson(raw == null ? "" : raw);
        if (text.isEmpty()) { p.error = "Model returned no JSON."; return p; }

        JsonNode root = null;
        try {
            root = MAPPER.readTree(text);
        } catch (Exception e) {
            try { root = MAPPER.readTree(CaseParser.repairCommonQuirks(text)); } catch (Exception ignored) {}
        }

        if (root != null && root.isObject()) {
            p.summary = root.path("summary").asText("").trim();
            for (String s : sections) {
                for (JsonNode n : root.path(s)) add(p, s, n);
            }
            return p;
        }

        // Cut off or unparseable: salvage section by section.
        p.salvaged = true;
        String repaired = CaseParser.repairCommonQuirks(text);
        for (String s : sections) {
            for (String obj : JsonSalvage.completeObjects(repaired, s)) {
                try { add(p, s, MAPPER.readTree(obj)); } catch (Exception e) { p.dropped++; }
            }
        }
        if (p.size() == 0) p.error = "Response was not valid JSON and nothing could be recovered.";
        return p;
    }

    private static void add(Parsed p, String section, JsonNode n) {
        ObjectNode item = n != null && n.isObject() ? normalize(section, (ObjectNode) n) : null;
        if (item == null) p.dropped++;
        else p.items.get(section).add(item);
    }

    /** Coerces one item into the section's shape, or null when it is unusable. */
    static ObjectNode normalize(String section, ObjectNode in) {
        ObjectNode o = MAPPER.createObjectNode();
        switch (section) {
            case "modules": {
                String singular = firstText(in, "singular_label", "label", "name");
                String plural = firstText(in, "plural_label", "api_name");
                if (singular.isEmpty() && plural.isEmpty()) return null;
                if (singular.isEmpty()) singular = plural;
                if (plural.isEmpty()) plural = singular.endsWith("s") ? singular : singular + "s";
                o.put("singular_label", singular);
                o.put("plural_label", plural);
                o.put("api_name", apiName(firstText(in, "api_name", "plural_label").isEmpty()
                        ? plural : firstText(in, "api_name", "plural_label")));
                String kind = text(in, "kind").toLowerCase(Locale.ROOT);
                o.put("kind", "standard".equals(kind) ? "standard" : "custom");
                o.put("purpose", text(in, "purpose"));
                break;
            }
            case "fields": {
                String module = text(in, "module");
                String label = firstText(in, "label", "field_label", "name");
                if (module.isEmpty() || label.isEmpty()) return null;
                o.put("module", module);
                o.put("label", label);
                o.put("api_name", apiName(firstText(in, "api_name").isEmpty() ? label : text(in, "api_name")));
                String type = text(in, "data_type").toLowerCase(Locale.ROOT).replace(" ", "");
                if ("double".equals(type) || "number".equals(type)) type = "decimal";
                if ("checkbox".equals(type)) type = "boolean";
                if ("multiselect".equals(type)) type = "multiselectpicklist";
                if ("url".equals(type)) type = "website";
                if (!KNOWN_FIELD_TYPES.contains(type)) {
                    o.put("note", "Unrecognised type \"" + text(in, "data_type") + "\"; treated as text.");
                    type = "text";
                }
                o.put("data_type", type);
                int len = in.path("length").asInt(0);
                if (len > 0) o.put("length", len);
                ArrayNode picks = o.putArray("picklist_values");
                for (JsonNode v : in.path("picklist_values")) {
                    String s = v.isObject() ? firstText(v, "display_value", "actual_value", "value") : v.asText("").trim();
                    if (!s.isEmpty()) picks.add(s);
                }
                if (type.contains("picklist") && picks.size() == 0) {
                    o.put("note", "Picklist has no values in the BRD; add them before applying.");
                }
                String lookup = text(in, "lookup_module");
                if (!lookup.isEmpty()) o.put("lookup_module", lookup);
                if (type.contains("lookup") && lookup.isEmpty()) {
                    o.put("note", "Lookup target module isn't stated; set lookup_module before applying.");
                }
                o.put("required", in.path("required").asBoolean(false));
                o.put("unique", in.path("unique").asBoolean(false));
                putIfText(o, "default_value", text(in, "default_value"));
                putIfText(o, "tooltip", text(in, "tooltip"));
                break;
            }
            case "layouts": {
                String module = text(in, "module");
                String name = firstText(in, "name", "layout");
                if (module.isEmpty()) return null;
                o.put("module", module);
                o.put("name", name.isEmpty() ? "Standard" : name);
                ArrayNode secs = o.putArray("sections");
                for (JsonNode s : in.path("sections")) {
                    ObjectNode so = secs.addObject();
                    so.put("name", s.isObject() ? text(s, "name") : s.asText(""));
                    ArrayNode fs = so.putArray("fields");
                    for (JsonNode f : s.path("fields")) fs.add(f.asText(""));
                }
                copyStrings(in, o, "profiles");
                o.put("notes", text(in, "notes"));
                break;
            }
            case "validation_rules": {
                String module = text(in, "module");
                String name = text(in, "name");
                String cond = firstText(in, "condition", "criteria");
                if (module.isEmpty() || (name.isEmpty() && cond.isEmpty())) return null;
                o.put("module", module);
                o.put("name", name.isEmpty() ? cond : name);
                o.put("condition", cond);
                o.put("error_message", text(in, "error_message"));
                break;
            }
            case "workflows": {
                String module = text(in, "module");
                String name = text(in, "name");
                if (name.isEmpty()) return null;
                String type = text(in, "type").toLowerCase(Locale.ROOT);
                o.put("type", "time_based".equals(type) ? "workflow" : WORKFLOW_TYPES.contains(type) ? type : "workflow");   // a time-based rule is a workflow rule
                o.put("module", module);
                o.put("name", name);
                o.put("trigger", text(in, "trigger"));
                o.put("criteria", text(in, "criteria"));
                copyStrings(in, o, "actions");
                copyStrings(in, o, "states");
                o.put("details", text(in, "details"));
                if (!text(in, "field").isEmpty()) o.put("field", text(in, "field"));
                if (in.has("default")) o.put("default", in.path("default").asBoolean(false));
                if (in.path("transitions").size() > 0) {
                    ArrayNode tr = o.putArray("transitions");
                    for (JsonNode t : in.path("transitions")) {
                        if (text(t, "from").isEmpty() || text(t, "to").isEmpty()) continue;
                        tr.addObject().put("name", text(t, "name")).put("from", text(t, "from")).put("to", text(t, "to"));
                    }
                }
                // Structured form, used to build the rule through the API (workflow type only).
                String trig = text(in, "trigger_type").toLowerCase(Locale.ROOT);
                o.put("trigger_type", TRIGGERS.contains(trig) ? trig : "");
                copyStrings(in, o, "trigger_fields");
                o.put("match", "or".equalsIgnoreCase(text(in, "match")) ? "or" : "and");
                ArrayNode conds = o.putArray("conditions");
                for (JsonNode c : in.path("conditions")) {
                    String field = text(c, "field");
                    String cmp = text(c, "comparator").toLowerCase(Locale.ROOT);
                    if (field.isEmpty() || !COMPARATORS.contains(cmp)) continue;
                    conds.addObject().put("field", field).put("comparator", cmp).put("value", text(c, "value"));
                }
                actionLists(in, o);
                // Time-based trigger: {field (date/datetime api name), offset (int, negative = before), period, time "HH:MM", recur}
                if (in.path("date_trigger").isObject() && !text(in.path("date_trigger"), "field").isEmpty()) {
                    JsonNode dt = in.path("date_trigger");
                    ObjectNode d = o.putObject("date_trigger");
                    d.put("field", text(dt, "field"));
                    d.put("offset", dt.path("offset").asInt(0));
                    String period = text(dt, "period").toLowerCase(Locale.ROOT).replace("business_days", "b_days").replace("business days", "b_days");
                    d.put("period", DATE_PERIODS.contains(period) ? period : "days");
                    d.put("time", text(dt, "time").matches("\\d{1,2}:\\d{2}") ? text(dt, "time") : "09:00");
                    String recur = text(dt, "recur").toLowerCase(Locale.ROOT);
                    d.put("recur", RECUR.contains(recur) ? recur : "once");
                    if (o.path("trigger_type").asText("").isEmpty()) o.put("trigger_type", "date_or_datetime");
                }
                // Delayed actions: [{after:{unit, period}, field_updates, tasks, webhooks}]
                ArrayNode sched = o.putArray("scheduled_actions");
                for (JsonNode sa : in.path("scheduled_actions")) {
                    JsonNode after = sa.path("after");
                    int unit = after.path("unit").asInt(sa.path("unit").asInt(0));
                    String period = text(after.isObject() ? after : sa, "period").toLowerCase(Locale.ROOT).replace(' ', '_');
                    if (unit <= 0 || !SCHEDULE_PERIODS.contains(period)) continue;
                    ObjectNode so = sched.addObject();
                    so.putObject("after").put("unit", unit).put("period", period);
                    actionLists(sa, so);
                    if (so.path("field_updates").size() + so.path("tasks").size() + so.path("webhooks").size() == 0) sched.remove(sched.size() - 1);
                }
                copyStrings(in, o, "other_actions");
                // Assignment rules: structured entries (users by email or full name)
                putIfText(o, "default_assignee", text(in, "default_assignee"));
                ArrayNode entries = o.putArray("rule_entries");
                for (JsonNode e : in.path("rule_entries")) {
                    ObjectNode en = entries.addObject();
                    en.put("match", "or".equalsIgnoreCase(text(e, "match")) ? "or" : "and");
                    ArrayNode ec = en.putArray("conditions");
                    for (JsonNode c : e.path("conditions")) {
                        String field = text(c, "field");
                        String cmp = text(c, "comparator").toLowerCase(Locale.ROOT);
                        if (field.isEmpty() || !COMPARATORS.contains(cmp)) continue;
                        ec.addObject().put("field", field).put("comparator", cmp).put("value", text(c, "value"));
                    }
                    copyStrings(e, en, "assign_to");
                    en.put("round_robin", e.path("round_robin").asBoolean(false));
                }
                // Plain-language mirror of the structured form, for the UI (the agent sends only the structured form).
                if (o.path("trigger").asText("").isEmpty() && !o.path("trigger_type").asText("").isEmpty()) {
                    String tt = o.path("trigger_type").asText();
                    String trigText = tt.equals("create") ? "On create" : tt.equals("edit") ? "On edit" : tt.equals("create_or_edit") ? "On create or edit"
                            : tt.equals("delete") ? "On delete" : tt.equals("field_update") ? "On update of " + String.join(", ", strings(o.path("trigger_fields")))
                            : o.has("date_trigger") ? dateTriggerText(o.path("date_trigger")) : "On a date/time";
                    o.put("trigger", trigText);
                }
                if (o.path("criteria").asText("").isEmpty() && conds.size() > 0) {
                    List<String> cs = new ArrayList<>();
                    for (JsonNode c : conds) cs.add(c.path("field").asText() + " " + c.path("comparator").asText().replace('_', ' ') + (c.path("value").asText("").isEmpty() ? "" : " " + c.path("value").asText()));
                    o.put("criteria", String.join(" " + o.path("match").asText("and") + " ", cs));
                }
                if (o.path("actions").size() == 0) {
                    ArrayNode acts = o.putArray("actions");
                    for (JsonNode f : o.path("field_updates")) acts.add("Set " + f.path("field").asText() + " to " + (f.path("value").isArray() ? f.path("value").toString() : f.path("value").asText()));
                    for (JsonNode t : o.path("tasks")) acts.add("Create task \"" + t.path("subject").asText() + "\"");
                    for (JsonNode h : o.path("webhooks")) acts.add("Call webhook " + h.path("http_method").asText() + " " + h.path("url").asText());
                    for (JsonNode a : o.path("other_actions")) acts.add(a.asText());
                    for (JsonNode sa : o.path("scheduled_actions")) {
                        String after = "After " + sa.path("after").path("unit").asInt() + " " + sa.path("after").path("period").asText().replace('_', ' ') + ": ";
                        for (JsonNode f : sa.path("field_updates")) acts.add(after + "set " + f.path("field").asText() + " to " + f.path("value").asText());
                        for (JsonNode t : sa.path("tasks")) acts.add(after + "create task \"" + t.path("subject").asText() + "\"");
                        for (JsonNode h : sa.path("webhooks")) acts.add(after + "call webhook " + h.path("url").asText());
                    }
                    if (acts.size() == 0 && entries.size() > 0) {
                        for (JsonNode e : entries) acts.add("Assign to " + String.join(", ", strings(e.path("assign_to"))) + (e.path("round_robin").asBoolean() ? " (round robin)" : ""));
                    }
                }
                break;
            }
            case "roles": {
                String name = text(in, "name");
                if (name.isEmpty()) return null;
                o.put("name", name);
                o.put("reports_to", text(in, "reports_to"));
                o.put("profile", text(in, "profile"));
                o.put("share_with_peers", in.path("share_with_peers").asBoolean(false));
                o.put("description", text(in, "description"));
                copyStrings(in, o, "permissions");
                break;
            }
            case "functions": {
                String name = text(in, "name");
                if (name.isEmpty()) return null;
                o.put("name", name.replaceAll("[^A-Za-z0-9_]", "_"));
                o.put("module", text(in, "module"));
                o.put("trigger", text(in, "trigger"));
                o.put("purpose", text(in, "purpose"));
                ArrayNode args = o.putArray("arguments");
                for (JsonNode a : in.path("arguments")) {
                    ObjectNode ao = args.addObject();
                    ao.put("name", a.isObject() ? text(a, "name") : a.asText(""));
                    ao.put("type", a.isObject() ? text(a, "type") : "string");
                }
                o.put("code", text(in, "code"));
                break;
            }
            case "open_questions": {
                String q = firstText(in, "question", "text");
                if (q.isEmpty()) return null;
                o.put("question", q);
                o.put("context", text(in, "context"));
                o.put("blocking", in.path("blocking").asBoolean(false));
                break;
            }
            case "plan": {
                String title = text(in, "title");
                if (title.isEmpty()) return null;
                o.put("phase", text(in, "phase"));
                o.put("title", title);
                o.put("description", text(in, "description"));
                o.put("owner_role", text(in, "owner_role"));
                o.put("effort_hours", Math.max(0, in.path("effort_hours").asDouble(0)));
                String pr = text(in, "priority").toUpperCase(Locale.ROOT);
                o.put("priority", pr.matches("P[012]") ? pr : "P1");
                putIfText(o, "lane", text(in, "lane"));
                putIfText(o, "verify", text(in, "verify"));
                copyStrings(in, o, "depends_on");
                copyStrings(in, o, "spec_refs");
                break;
            }
            default:
                return null;
        }
        putIfText(o, "assumption", text(in, "assumption"));
        ArrayNode ids = o.putArray("req_ids");
        for (JsonNode id : in.path("req_ids")) {
            String s = Requirements.normalizeId(id.asText(""));
            if (!s.isEmpty()) ids.add(s);
        }
        return o;
    }

    /** De-duplication key within a section. */
    static String key(String section, JsonNode o) {
        switch (section) {
            case "modules":          return low(o.path("api_name").asText());
            case "fields":           return low(o.path("module").asText()) + "|" + low(o.path("api_name").asText());
            case "layouts":          return low(o.path("module").asText()) + "|" + low(o.path("name").asText());
            case "validation_rules": return low(o.path("module").asText()) + "|" + low(o.path("name").asText());
            case "workflows":        return low(o.path("module").asText()) + "|" + low(o.path("name").asText());
            case "roles":            return low(o.path("name").asText());
            case "functions":        return low(o.path("name").asText());
            case "open_questions":   return low(o.path("question").asText());
            case "plan":             return low(o.path("title").asText());
            default:                 return low(o.toString());
        }
    }

    /**
     * Merges items into spec[section], skipping duplicates. A duplicate's req_ids are
     * added to the item already kept. Returns how many duplicates were folded in.
     */
    public static int merge(ObjectNode spec, String section, List<ObjectNode> items, Map<String, ObjectNode> seen) {
        ArrayNode arr = spec.has(section) ? (ArrayNode) spec.get(section) : spec.putArray(section);
        int dups = 0;
        for (ObjectNode item : items) {
            String k = section + "#" + key(section, item);
            ObjectNode kept = seen.get(k);
            if (kept != null) {
                dups++;
                ArrayNode ids = (ArrayNode) kept.get("req_ids");
                Set<String> have = new HashSet<>();
                ids.forEach(i -> have.add(i.asText()));
                for (JsonNode i : item.path("req_ids")) if (have.add(i.asText())) ids.add(i.asText());
                continue;
            }
            seen.put(k, item);
            arr.add(item);
        }
        return dups;
    }

    /** Assigns id, executor and include to every item. Status is set by ImplementServlet. */
    public static void finish(ObjectNode spec) {
        for (String s : SECTIONS) {
            ArrayNode arr = spec.has(s) ? (ArrayNode) spec.get(s) : spec.putArray(s);
            int i = 1;
            for (JsonNode n : arr) {
                ObjectNode o = (ObjectNode) n;
                if (!o.hasNonNull("id")) o.put("id", PREFIX.get(s) + "-" + (i));
                i++;
                o.put("executor", executorFor(s, o));
                if (!o.has("include")) o.put("include", true);
            }
        }
    }

    static String executorFor(String section, JsonNode o) {
        return Products.current().laneFor(section, o);
    }

    /** CRM-style api name from a label: "Lead Score %" -> "Lead_Score". */
    public static String apiName(String label) {
        String s = label == null ? "" : label.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) return "Field";
        if (Character.isDigit(s.charAt(0))) s = "F_" + s;
        return s.length() > 50 ? s.substring(0, 50) : s;
    }

    // ---- helpers ----

    static String low(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", " ").trim();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? "" : v.asText("").trim();
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) for (JsonNode v : arr) out.add(v.asText(""));
        return out;
    }

    private static void putIfText(ObjectNode o, String field, String value) {
        if (value != null && !value.isEmpty()) o.put(field, value);
    }

    private static void copyStrings(JsonNode in, ObjectNode out, String field) {
        ArrayNode arr = out.putArray(field);
        JsonNode src = in.path(field);
        if (src.isArray()) {
            for (JsonNode v : src) {
                String s = v.isObject() ? v.toString() : v.asText("").trim();
                if (!s.isEmpty()) arr.add(s);
            }
        } else if (src.isTextual() && !src.asText().trim().isEmpty()) {
            arr.add(src.asText().trim());
        }
    }
}
