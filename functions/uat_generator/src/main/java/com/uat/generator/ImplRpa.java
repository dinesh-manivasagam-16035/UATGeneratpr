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
            case "global_picklists":
                s.add(Products.current().setupRoot("fields") + " → Global Picklists → New Global Set.");
                s.add("Name: " + o.path("name").asText() + ".");
                s.add("Options in order: " + join(o.path("values")) + ".");
                s.add("Save.");
                break;
            case "dependencies": {
                s.add(Products.current().setupRoot("fields") + " → " + module + " → Layouts → Standard → Map Dependency Fields → New Mapping.");
                s.add("Parent: " + o.path("parent").asText() + ", child: " + o.path("child").asText() + ".");
                java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = o.path("mapping").fields();
                while (it.hasNext()) { java.util.Map.Entry<String, JsonNode> e = it.next(); s.add(e.getKey() + " → " + join(e.getValue()) + "."); }
                s.add("Save.");
                break;
            }
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
        String modPath = module.isEmpty() ? "" : "/" + module;
        switch (section) {
            case "validation_rules": {
                // Deep link straight to the module's validation rules (Setup → Customization → Modules and Fields → <module> → Validation Rules).
                op(r, "goto", "path", "/settings/modules" + modPath + "/validation-rules");
                op(r, "click", "text", "Create Rule|New Rule|Create Validation Rule|\\+ Rule|Create");
                op(r, "fill", "label", "Rule Name|Name", "value", o.path("name").asText());
                java.util.List<String[]> conds = conditions(o.path("condition").asText(""));
                for (int i = 0; i < conds.size(); i++) {
                    String[] c = conds.get(i);
                    if (i > 0) op(r, "click", "text", "Add criteria|Add condition|Add another|\\+");
                    if (!c[0].isEmpty()) op(r, "choose", "near", "Field|Choose field|Select field|Pick field", "option", c[0]);
                    if (!c[1].isEmpty()) op(r, "choose", "near", "Condition|Operator|is", "option", c[1]);
                    if (!c[2].isEmpty()) op(r, "fill", "label", "Value|Enter value|Choose value", "value", c[2]);
                }
                if (conds.size() > 1) op(r, "choose", "near", "Pattern|Criteria pattern|Match", "option", joinWord(o.path("condition").asText("")));
                op(r, "click", "text", "Next|Continue");
                op(r, "fill", "label", "Alert|Error message|Alert message|Message", "value", o.path("error_message").asText());
                op(r, "confirm", "text", "Check the rule (" + o.path("condition").asText("") + " → \"" + o.path("error_message").asText("") + "\"), then approve to save.");
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
                op(r, "confirm", "text", "Deluge pasted. Check it compiles (Save & Execute), then approve.");
                op(r, "click", "text", "Save");
                break;
            }
            case "fields": {
                // Field types with no create API (formula, autonumber, file upload, multi-lookup): the bot opens the
                // layout editor and hands over, with the exact values to enter.
                op(r, "goto", "path", "/settings/modules" + modPath + "/layouts");
                op(r, "click", "text", "Standard|Standard Layout");
                op(r, "confirm", "text", "Drag a \"" + o.path("data_type").asText() + "\" field onto the layout, label it \"" + o.path("label").asText() + "\""
                        + (o.path("required").asBoolean(false) ? ", tick Mandatory" : "") + ", save the layout, then approve.");
                break;
            }
            case "layouts": {
                op(r, "goto", "path", "/settings/modules" + modPath + "/layouts");
                op(r, "confirm", "text", "Open or create layout \"" + o.path("name").asText("Standard") + "\", place the fields per section as listed"
                        + (o.path("profiles").size() > 0 ? ", assign profiles " + join(o.path("profiles")) : "") + ", save, then approve.");
                break;
            }
            case "dependencies": {
                op(r, "goto", "path", "/settings/modules" + modPath + "/layouts");
                op(r, "confirm", "text", "Open the Standard layout → Map Dependency Fields → New Mapping: parent " + o.path("parent").asText() + ", child " + o.path("child").asText() + ", map each option as the spec lists, save, then approve.");
                break;
            }
            case "workflows": {
                String type = o.path("type").asText("workflow");
                op(r, "goto", "path", automationPath(type));
                op(r, "click", "text", "Create Rule|New Rule|Create Blueprint|New Blueprint|Create Approval Process|New Scoring Rule|Create Schedule|Create Cadence|Configure Webhook|Create|New");
                op(r, "fill", "label", "Rule Name|Name|Blueprint Name|Process Name", "value", o.path("name").asText());
                if (!module.isEmpty()) op(r, "choose", "near", "Module|Choose module|Select module", "option", module);
                StringBuilder what = new StringBuilder();
                if (!o.path("trigger").asText("").isEmpty()) what.append("Trigger: ").append(o.path("trigger").asText()).append(". ");
                if (!o.path("criteria").asText("").isEmpty()) what.append("Criteria: ").append(o.path("criteria").asText()).append(". ");
                if (o.path("states").size() > 0) what.append("States: ").append(join(o.path("states"))).append(". ");
                for (JsonNode a : o.path("actions")) what.append("Action: ").append(a.asText()).append(". ");
                if (!o.path("details").asText("").isEmpty()) what.append(o.path("details").asText());
                op(r, "confirm", "text", "Complete the " + type + " in Setup — " + what.toString().trim() + " Save and activate, then approve.");
                break;
            }
            default:
                break;
        }
        return r;
    }

    /** Setup deep link for an automation type (CRM). */
    static String automationPath(String type) {
        switch (type == null ? "" : type) {
            case "blueprint":  return "/settings/blueprint";
            case "approval":   return "/settings/approval-process";
            case "assignment": return "/settings/assignment-rules";
            case "scoring":    return "/settings/scoring-rules";
            case "schedule":   return "/settings/schedules";
            case "cadence":    return "/settings/cadences";
            case "webhook":    return "/settings/webhooks";
            default:           return "/settings/workflow-rules";
        }
    }

    /** "PIDG Code is empty and Lead Status is 'Qualified'" → [[PIDG Code, is empty, ""], [Lead Status, is, Qualified]] */
    static java.util.List<String[]> conditions(String condition) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String c = condition == null ? "" : condition.trim().replaceAll("^(when|if)\\s+", "");
        for (String part : c.split("(?i)\\s+(and|or)\\s+")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            String field = fieldLabel(part);
            String word = conditionWord(part);
            String value = "";
            if (!word.isEmpty() && !word.endsWith("empty")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:is not|is|equals|==|!=|<>|>=|<=|>|<|contains)\\s*['\"“”]?([^'\"“”]+?)['\"“”]?\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(part);
                if (m.find()) value = m.group(1).trim();
            }
            out.add(new String[]{field, word, value});
        }
        return out;
    }

    static String joinWord(String condition) {
        return condition != null && condition.toLowerCase(Locale.ROOT).matches(".*\\s+or\\s+.*") ? "OR" : "AND";
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

    private static String menuFor(String type) { return Products.current().automationMenu(type); }

    private static String join(JsonNode arr) {
        List<String> s = new ArrayList<>();
        for (JsonNode v : arr) s.add(v.asText());
        return String.join(", ", s);
    }
}
