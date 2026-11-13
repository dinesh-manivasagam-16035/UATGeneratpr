package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Function;

/**
 * Request bodies for building a workflow rule through the Zoho CRM v8 API. A rule references
 * its actions by id, so a build is three kinds of call:
 *
 *   POST /settings/automation/field_updates   one per field update   → action id
 *   POST /settings/automation/tasks           one per task           → action id
 *   POST /settings/automation/workflow_rules  the rule, referencing those ids
 *
 * Shapes follow zoho.com/crm/developer/docs/api/v8/ (config-workflow, create-field-update,
 * create-automation-task). Field and module ids are mandatory there; the caller resolves them
 * from the live org (fieldId returns null for an unknown field, which is reported, not guessed).
 */
public final class ImplRules {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplRules() {}

    /** Rule names may not contain # % ^ & * ( ) and are at most 100 characters. */
    public static String ruleName(String name) {
        String s = (name == null ? "" : name).replaceAll("[#%^&*()]", "").trim();
        return s.length() > 100 ? s.substring(0, 100).trim() : s;
    }

    private static ObjectNode ref(String apiName, String id) {
        ObjectNode o = MAPPER.createObjectNode().put("api_name", apiName);
        if (id != null && !id.isEmpty()) o.put("id", id);
        return o;
    }

    /** {"field_updates":[{module, type:static, feature_type:workflow, field, name, value}]} */
    public static ObjectNode fieldUpdate(String moduleApi, String moduleId, String rule, JsonNode live, JsonNode value) {
        ObjectNode fu = MAPPER.createObjectNode();
        fu.set("module", ref(moduleApi, moduleId));
        fu.put("type", "static");
        fu.put("feature_type", "workflow");
        fu.set("field", ref(live.path("api_name").asText(), live.path("id").asText()));
        fu.put("name", trim(rule + " - set " + live.path("field_label").asText(live.path("api_name").asText()), 100));
        boolean multi = "multiselectpicklist".equals(live.path("data_type").asText());
        if (multi) {
            ArrayNode arr = fu.putArray("value");
            if (value.isArray()) value.forEach(v -> arr.add(v.asText()));
            else for (String v : value.asText("").split(",")) if (!v.trim().isEmpty()) arr.add(v.trim());
            fu.put("update_type", "append");
        } else {
            fu.put("value", value.isArray() && value.size() > 0 ? value.get(0).asText() : value.asText(""));
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("field_updates").add(fu);
        return body;
    }

    /**
     * {"tasks":[{module, feature_type:workflow, name, field_mappings:[Subject, Due_Date, Status, Priority?]}]}
     * taskField resolves a Tasks-module field api name to the live field (id needed).
     */
    public static ObjectNode task(String moduleApi, String moduleId, JsonNode t, Function<String, JsonNode> taskField) {
        ObjectNode task = MAPPER.createObjectNode();
        task.set("module", ref(moduleApi, moduleId));
        task.put("feature_type", "workflow");
        task.put("name", trim(t.path("subject").asText(), 100));
        ArrayNode maps = task.putArray("field_mappings");
        maps.add(mapping(taskField, "Subject", "static").put("value", t.path("subject").asText()));
        ObjectNode due = mapping(taskField, "Due_Date", "execution_time");
        due.putObject("value").put("period", "days").put("unit", String.valueOf(t.path("due_in_days").asInt(0)))
                .put("trigger_field", "${CURRENTTIME}").put("sign", "plus");
        maps.add(due);
        maps.add(mapping(taskField, "Status", "static").put("value", "Not Started"));
        if (!t.path("priority").asText("").isEmpty()) {
            maps.add(mapping(taskField, "Priority", "static").put("value", t.path("priority").asText()));
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("tasks").add(task);
        return body;
    }

    private static ObjectNode mapping(Function<String, JsonNode> taskField, String api, String type) {
        JsonNode f = taskField.apply(api);
        if (f == null) throw new IllegalStateException("Tasks module has no field " + api + " (needed for the task action).");
        ObjectNode m = MAPPER.createObjectNode();
        m.set("field", ref(api, f.path("id").asText()));
        m.put("type", type);
        return m;
    }

    /**
     * {"workflow_rules":[{execute_when, module, name, description, conditions:[{sequence_number:1,
     * criteria_details, instant_actions:{actions:[{type,id}...]}}]}]}
     * fieldId resolves a field api name of the rule's module to the live field (null when unknown).
     */
    public static ObjectNode rule(JsonNode wf, String moduleApi, String moduleId,
                                  Function<String, JsonNode> fieldOf, List<String[]> actions) {
        return rule(wf, moduleApi, moduleId, fieldOf, actions, java.util.Collections.emptyList(), "+00:00");
    }

    /** One delayed block of a rule: how long after the trigger, and the created actions (type, id). */
    public static final class Scheduled {
        public final int unit; public final String period; public final List<String[]> actions;
        public Scheduled(int unit, String period, List<String[]> actions) { this.unit = unit; this.period = period; this.actions = actions; }
    }

    /**
     * @param scheduled  delayed action blocks (scheduled_actions)
     * @param utcOffset  the org's UTC offset ("+05:30") for a date trigger's execute_at
     */
    public static ObjectNode rule(JsonNode wf, String moduleApi, String moduleId,
                                  Function<String, JsonNode> fieldOf, List<String[]> actions,
                                  List<Scheduled> scheduled, String utcOffset) {
        String trigger = wf.path("trigger_type").asText("");
        ObjectNode when = MAPPER.createObjectNode().put("type", trigger);
        if ("date_or_datetime".equals(trigger)) {
            JsonNode dt = wf.path("date_trigger");
            if (!dt.isObject() || dt.path("field").asText("").isEmpty()) {
                throw new IllegalStateException("A time-based rule needs date_trigger.field (the date/datetime field it counts from).");
            }
            JsonNode f = need(fieldOf, dt.path("field").asText(), moduleApi);
            ObjectNode details = when.putObject("details");
            details.set("field", ref(f.path("api_name").asText(), f.path("id").asText()));
            details.put("unit", dt.path("offset").asInt(0));
            details.put("period", dt.path("period").asText("days"));
            details.put("execute_at", String.format("%s:00%s", dt.path("time").asText("09:00").length() == 4 ? "0" + dt.path("time").asText() : dt.path("time").asText("09:00"), utcOffset));
            details.put("recur_cycle", dt.path("recur").asText("once"));
            details.put("repeat", false);
            details.set("trigger_module", ref(moduleApi, moduleId));
        } else if ("create".equals(trigger) || "delete".equals(trigger)) {
            when.putObject("details").set("trigger_module", ref(moduleApi, moduleId));
        } else if ("edit".equals(trigger) || "create_or_edit".equals(trigger)) {
            when.putObject("details").put("repeat", false).set("trigger_module", ref(moduleApi, moduleId));
        } else if ("field_update".equals(trigger)) {
            if (wf.path("trigger_fields").size() == 0) {
                throw new IllegalStateException("A field-update trigger needs trigger_fields (the fields whose change fires it).");
            }
            ObjectNode details = when.putObject("details");
            details.set("trigger_module", ref(moduleApi, moduleId));
            ObjectNode crit = details.putObject("criteria").put("group_operator", "OR");
            ArrayNode group = crit.putArray("group");
            for (JsonNode tf : wf.path("trigger_fields")) {
                JsonNode f = need(fieldOf, tf.asText(), moduleApi);
                group.addObject().put("comparator", "${ANYVALUE}")
                        .put("value", "${ANYVALUE}")
                        .set("field", ref(f.path("api_name").asText(), f.path("id").asText()));
            }
            details.put("repeat", false);
            details.put("match_all", false);
        }

        ObjectNode r = MAPPER.createObjectNode();
        r.set("execute_when", when);
        r.set("module", ref(moduleApi, null));
        r.put("name", ruleName(wf.path("name").asText()));
        String desc = wf.path("criteria").asText("");
        if (!desc.isEmpty()) r.put("description", trim(desc, 500));

        ObjectNode cond = r.putArray("conditions").addObject();
        cond.put("sequence_number", 1);
        ObjectNode cd = cond.putObject("criteria_details");
        JsonNode conds = wf.path("conditions");
        if (conds.size() == 0) {
            cd.putNull("criteria");                       // all records
        } else if (conds.size() == 1) {
            cd.set("criteria", criterion(conds.get(0), fieldOf, moduleApi));
        } else {
            ObjectNode g = cd.putObject("criteria").put("group_operator", wf.path("match").asText("and").toUpperCase());
            ArrayNode arr = g.putArray("group");
            for (JsonNode c : conds) arr.add(criterion(c, fieldOf, moduleApi));
        }
        ArrayNode acts = cond.putObject("instant_actions").putArray("actions");
        for (String[] a : actions) acts.addObject().put("type", a[0]).put("id", a[1]);
        if (!scheduled.isEmpty()) {
            ArrayNode sch = cond.putArray("scheduled_actions");
            for (Scheduled s : scheduled) {
                ObjectNode block = sch.addObject();
                block.putObject("execute_after").put("period", s.period).put("unit", s.unit);
                ArrayNode sa = block.putArray("actions");
                for (String[] a : s.actions) sa.addObject().put("type", a[0]).put("id", a[1]);
            }
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("workflow_rules").add(r);
        return body;
    }

    /** {"webhooks":[{module, name, url, http_method, authentication:{type:general}, description?}]} (Create Webhook API). */
    public static ObjectNode webhook(String moduleApi, String moduleId, String rule, JsonNode w) {
        ObjectNode h = MAPPER.createObjectNode();
        h.set("module", ref(moduleApi, moduleId));
        String name = w.path("name").asText("");
        h.put("name", trim(name.isEmpty() ? rule + " - webhook" : name, 100));
        h.put("url", w.path("url").asText(""));
        String method = w.path("http_method").asText("POST").toUpperCase();
        h.put("http_method", method.matches("GET|POST|PUT|PATCH|DELETE") ? method : "POST");
        h.putObject("authentication").put("type", "general");
        if (!w.path("description").asText("").isEmpty()) h.put("description", trim(w.path("description").asText(), 250));
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("webhooks").add(h);
        return body;
    }

    /**
     * {"assignment_rules":[{name, description?, default_assignee:{type:user,resource:{id}}, rule_entries:[{criteria, assign_to}]}]}
     * (Create Assignment Rule API). userIdOf resolves an email or full name to a user id (null = unknown, reported).
     */
    public static ObjectNode assignmentRule(JsonNode wf, String moduleApi, Function<String, JsonNode> fieldOf,
                                            Function<String, String> userIdOf) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("name", ruleName(wf.path("name").asText()));
        if (!wf.path("criteria").asText("").isEmpty()) r.put("description", trim(wf.path("criteria").asText(), 250));
        String def = wf.path("default_assignee").asText("");
        ObjectNode da = r.putObject("default_assignee").put("type", "user");
        if (def.isEmpty() || def.toLowerCase().contains("current")) da.putObject("resource").put("api_name", "${CURRENTUSER}");
        else da.putObject("resource").put("id", needUser(userIdOf, def));
        ArrayNode entries = r.putArray("rule_entries");
        for (JsonNode e : wf.path("rule_entries")) {
            ObjectNode en = entries.addObject();
            JsonNode conds = e.path("conditions");
            if (conds.size() == 0) throw new IllegalStateException("An assignment rule entry needs at least one condition.");
            if (conds.size() == 1) en.set("criteria", criterion(conds.get(0), fieldOf, moduleApi));
            else {
                ObjectNode g = en.putObject("criteria").put("group_operator", e.path("match").asText("and").toUpperCase());
                ArrayNode arr = g.putArray("group");
                for (JsonNode c : conds) arr.add(criterion(c, fieldOf, moduleApi));
            }
            ObjectNode to = en.putObject("assign_to");
            ArrayNode res = to.putArray("resources");
            for (JsonNode u : e.path("assign_to")) res.addObject().put("id", needUser(userIdOf, u.asText()));
            if (res.size() == 0) throw new IllegalStateException("An assignment rule entry needs assign_to users.");
            to.put("type", "users");
            if (e.path("round_robin").asBoolean(false) && res.size() > 1) to.put("type", "round_robin");
        }
        if (entries.size() == 0) throw new IllegalStateException("An assignment rule needs rule_entries.");
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("assignment_rules").add(r);
        return body;
    }

    private static String needUser(Function<String, String> userIdOf, String who) {
        String id = userIdOf.apply(who);
        if (id == null || id.isEmpty()) throw new IllegalStateException("No active CRM user matches \"" + who + "\" (use the user's email or full name).");
        return id;
    }

    private static ObjectNode criterion(JsonNode c, Function<String, JsonNode> fieldOf, String moduleApi) {
        JsonNode f = need(fieldOf, c.path("field").asText(), moduleApi);
        String cmp = c.path("comparator").asText("equal");
        String value = c.path("value").asText("");
        if ("is_empty".equals(cmp))      { cmp = "equal"; value = "${EMPTY}"; }
        if ("is_not_empty".equals(cmp))  { cmp = "equal"; value = "${NOTEMPTY}"; }
        ObjectNode o = MAPPER.createObjectNode();
        o.put("comparator", cmp);
        o.set("field", ref(f.path("api_name").asText(), f.path("id").asText()));
        o.put("type", "value");
        o.put("value", value);
        return o;
    }

    private static JsonNode need(Function<String, JsonNode> fieldOf, String api, String module) {
        JsonNode f = fieldOf.apply(api);
        if (f == null) throw new IllegalStateException("Field " + api + " isn't in " + module + " yet (build it first, or fix the name).");
        return f;
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max).trim() : s;
    }
}
