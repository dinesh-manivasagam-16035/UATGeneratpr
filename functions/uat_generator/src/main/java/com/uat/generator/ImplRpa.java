package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

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
                s.add("Setup → Customization → Modules and Fields → " + module + " → Standard layout.");
                s.add("Drag a \"" + o.path("data_type").asText() + "\" field onto the layout, label it \""
                        + o.path("label").asText() + "\".");
                if (o.path("lookup_module").isTextual()) s.add("Target module: " + o.path("lookup_module").asText() + ".");
                if (o.path("required").asBoolean(false)) s.add("Tick Mandatory.");
                s.add("Save the layout.");
                break;
            case "layouts":
                s.add("Setup → Customization → Modules and Fields → " + module + " → Layouts → "
                        + (o.path("name").asText("Standard").equalsIgnoreCase("standard") ? "Standard" : "New Layout \"" + o.path("name").asText() + "\"") + ".");
                for (JsonNode sec : o.path("sections")) {
                    s.add("Section \"" + sec.path("name").asText() + "\": " + join(sec.path("fields")) + ".");
                }
                if (o.path("profiles").size() > 0) s.add("Assign to profiles: " + join(o.path("profiles")) + ".");
                if (!o.path("notes").asText("").isEmpty()) s.add(o.path("notes").asText());
                s.add("Save the layout.");
                break;
            case "validation_rules":
                s.add("Setup → Customization → Modules and Fields → " + module + " → Validation Rules → Create Rule.");
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
