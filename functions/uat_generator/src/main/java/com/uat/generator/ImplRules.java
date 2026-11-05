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
        String trigger = wf.path("trigger_type").asText("");
        ObjectNode when = MAPPER.createObjectNode().put("type", trigger);
        if ("edit".equals(trigger) || "create_or_edit".equals(trigger)) {
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

        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("workflow_rules").add(r);
        return body;
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
