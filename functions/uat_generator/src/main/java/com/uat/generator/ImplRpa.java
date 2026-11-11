package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Click-path instructions for items that have no reliable create API. They are
 * written for a person in Zoho CRM Setup, and are specific enough for an RPA bot
 * script to follow (menu path, then the values to enter).
 */
public final class ImplRpa {

    private ImplRpa() {}

    public static List<String> steps(String section, JsonNode o) {
        List<String> s = new ArrayList<>();
        String module = o.path("module").asText("");
        switch (section) {
            case "fields":
                s.add(Products.current().setupRoot("fields") + " → " + module + " → Standard layout.");
                s.add("Drag a \"" + o.path("data_type").asText() + "\" field onto the layout, label it \""
                        + o.path("label").asText() + "\".");
                if (o.path("lookup_module").isTextual()) s.add("Target module: " + o.path("lookup_module").asText() + ".");
                if (o.path("required").asBoolean(false)) s.add("Tick Mandatory.");
                s.add("Save the layout.");
                break;
            case "layouts":
                s.add(Products.current().setupRoot("layouts") + " → " + module + " → Layouts → "
                        + (o.path("name").asText("Standard").equalsIgnoreCase("standard") ? "Standard" : "New Layout \"" + o.path("name").asText() + "\"") + ".");
                for (JsonNode sec : o.path("sections")) {
                    s.add("Section \"" + sec.path("name").asText() + "\": " + join(sec.path("fields")) + ".");
                }
                if (o.path("profiles").size() > 0) s.add("Assign to profiles: " + join(o.path("profiles")) + ".");
                if (!o.path("notes").asText("").isEmpty()) s.add(o.path("notes").asText());
                s.add("Save the layout.");
                break;
            case "validation_rules":
                s.add(Products.current().setupRoot("validation_rules") + " → " + module + " → Validation Rules → Create Rule.");
                s.add("Name: " + o.path("name").asText() + ".");
                s.add("Condition (reject when): " + o.path("condition").asText() + ".");
                s.add("Alert message: \"" + o.path("error_message").asText() + "\".");
                s.add("Save.");
                break;
            case "workflows": {
                String type = o.path("type").asText("workflow");
                s.add(menuFor(type) + (module.isEmpty() ? "" : " Module: " + module + "."));
                s.add("Name: " + o.path("name").asText() + ".");
                if (!o.path("trigger").asText("").isEmpty()) s.add("Trigger: " + o.path("trigger").asText() + ".");
                if (!o.path("criteria").asText("").isEmpty()) s.add("Criteria: " + o.path("criteria").asText() + ".");
                if (o.path("states").size() > 0) s.add("States in order: " + join(o.path("states")) + ".");
                for (JsonNode a : o.path("actions")) s.add("Action: " + a.asText() + ".");
                if (!o.path("details").asText("").isEmpty()) s.add(o.path("details").asText());
                s.add("Save and activate.");
                break;
            }
            case "functions":
                s.add("Setup → Developer Hub → Functions → New Function. Name: " + o.path("name").asText()
                        + ", category: " + (o.path("trigger").asText("").toLowerCase().contains("button") ? "Button" : "Automation") + ".");
                if (o.path("arguments").size() > 0) {
                    List<String> args = new ArrayList<>();
                    for (JsonNode a : o.path("arguments")) args.add(a.path("name").asText() + " (" + a.path("type").asText() + ")");
                    s.add("Arguments: " + String.join(", ", args) + ".");
                }
                s.add("Paste the Deluge code from the spec, Save & Execute with a test record.");
                if (!o.path("trigger").asText("").isEmpty()) s.add("Wire it up: " + o.path("trigger").asText() + ".");
                break;
            default:
                s.add("Build as described in the spec.");
        }
        return s;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Machine-readable recipe for the runner script: a list of UI operations it performs itself in
     * Zoho CRM Setup, pausing for the person only where an element can't be found or before a save.
     * Ops: goto{path} · click{text} · fill{label, value} · choose{near, option} · type{value}
     *      · expect{text} · confirm{text} (pause so the person can check before the save)
     * An empty recipe means "manual": the runner shows the steps and pauses.
     */
    public static ArrayNode recipe(String section, JsonNode o) {
        ArrayNode r = MAPPER.createArrayNode();
        String module = o.path("module").asText("");
        switch (section) {
            case "validation_rules": {
                String field = fieldLabel(o.path("condition").asText(""));
                String cond = conditionWord(o.path("condition").asText(""));
                op(r, "goto", "path", "/settings/modules");
                op(r, "click", "text", module);
                op(r, "click", "text", "Validation Rules");
                op(r, "click", "text", "Create Rule|New Rule|Create Validation Rule|\\+ Rule");
                op(r, "fill", "label", "Rule Name|Name", "value", o.path("name").asText());
                if (!field.isEmpty()) op(r, "choose", "near", "Field|Choose field|Select field", "option", field);
                if (!cond.isEmpty()) op(r, "choose", "near", "Condition|Operator|is", "option", cond);
                op(r, "click", "text", "Next|Continue");
                op(r, "fill", "label", "Alert|Error message|Alert message|Message", "value", o.path("error_message").asText());
                op(r, "confirm", "text", "Check the rule, then Resume to save (or press Save yourself).");
                op(r, "click", "text", "Save");
                op(r, "expect", "text", o.path("name").asText());
                break;
            }
            case "functions": {
                op(r, "goto", "path", "/settings/functions");
                op(r, "click", "text", "New Function|Create Function|\\+ New Function");
                op(r, "fill", "label", "Function Name|Name", "value", o.path("name").asText());
                op(r, "fill", "label", "Display Name", "value", o.path("name").asText().replace('_', ' '));
                op(r, "choose", "near", "Category", "option", o.path("trigger").asText("").toLowerCase(Locale.ROOT).contains("button") ? "Button" : "Automation");
                op(r, "click", "text", "Create|Next");
                op(r, "type", "value", o.path("code").asText(""));
                op(r, "confirm", "text", "Deluge pasted. Check it compiles (Save & Execute), then Resume.");
                op(r, "click", "text", "Save");
                break;
            }
            default:
                break;   // manual: blueprints, approvals, per-profile layouts, unknown field types
        }
        return r;
    }

    private static void op(ArrayNode r, String op, String... kv) {
        ObjectNode o = r.addObject().put("op", op);
        for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
    }

    /** "PIDG Code is empty" → "PIDG Code"; "Amount > 1000" → "Amount". */
    static String fieldLabel(String condition) {
        String c = condition == null ? "" : condition.trim().replaceAll("^(when|if)\\s+", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\"?([A-Za-z][A-Za-z0-9 _/()%-]*?)\"?\\s*(is\\b|==|!=|<>|>=|<=|=|>|<|contains\\b|equals\\b|starts\\b|ends\\b|not\\b)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(c);
        return m.find() ? m.group(1).trim() : "";
    }

    static String conditionWord(String condition) {
        String c = condition == null ? "" : condition.toLowerCase(Locale.ROOT);
        if (c.contains("is not empty") || c.contains("is not blank") || c.contains("not null")) return "is not empty";
        if (c.contains("is empty") || c.contains("is blank") || c.contains("is null")) return "is empty";
        if (c.contains("not equal") || c.contains("!=") || c.contains("<>")) return "is not";
        if (c.contains("contains")) return "contains";
        if (c.contains(">=")) return ">=";
        if (c.contains("<=")) return "<=";
        if (c.contains(">") || c.contains("greater")) return ">";
        if (c.contains("<") || c.contains("less")) return "<";
        if (c.contains(" is ") || c.contains("=") || c.contains("equals")) return "is";
        return "";
    }

    /** What a person still does after the API created a "partial" item. */
    public static List<String> followUp(String section, JsonNode o) {
        List<String> s = new ArrayList<>();
        if ("fields".equals(section)) {
            s.add("Setup → Customization → Modules and Fields → " + o.path("module").asText() + " → Standard layout.");
            s.add("Open \"" + o.path("label").asText() + "\" → Mark as required. Save the layout.");
        } else if ("roles".equals(section)) {
            s.add("Setup → Security Control → Profiles → " + (o.path("profile").asText("").isEmpty()
                    ? "the profile for " + o.path("name").asText() : o.path("profile").asText()) + ".");
            for (JsonNode p : o.path("permissions")) s.add("Allow: " + p.asText() + ".");
            s.add("Save, and assign users with role " + o.path("name").asText() + " to that profile.");
        } else if ("workflows".equals(section)) {
            s.add("Setup → Automation → Workflow Rules → " + ImplRules.ruleName(o.path("name").asText()) + " → Edit.");
            for (JsonNode a : o.path("other_actions")) s.add("Add action: " + a.asText() + ".");
            s.add("Save.");
        } else {
            s.add("Finish as described in the blueprint.");
        }
        return s;
    }

    private static String menuFor(String type) {
        switch (type) {
            case "blueprint":  return "Setup → Process Management → Blueprint → Create Blueprint.";
            case "assignment": return "Setup → Automation → Assignment Rules → New Rule.";
            case "approval":   return "Setup → Process Management → Approval Processes → Create Approval Process.";
            case "scoring":    return "Setup → Automation → Scoring Rules → New Scoring Rule.";
            case "escalation": return "Setup → Automation → Workflow Rules → Create Rule (date/time based) for the escalation.";
            case "schedule":   return "Setup → Automation → Schedules → Create Schedule.";
            case "cadence":    return "Setup → Automation → Cadences → Create Cadence.";
            case "webhook":    return "Setup → Automation → Actions → Webhooks → Configure Webhook.";
            default:           return "Setup → Automation → Workflow Rules → Create Rule.";
        }
    }

    private static String join(JsonNode arr) {
        List<String> s = new ArrayList<>();
        for (JsonNode v : arr) s.add(v.asText());
        return String.join(", ", s);
    }
}
