package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Zoho Desk adapter — stage A: connect and read the org (departments, ticket/contact/account fields),
 * brief the agent in Desk vocabulary with Desk's capability matrix, compare items with the org.
 * API builders come in stage B once each endpoint's request shape is confirmed against the Desk API
 * reference; until then every build item is queued for the guided runner with Desk Setup paths.
 */
public final class DeskProduct implements Product {

    static final DeskProduct INSTANCE = new DeskProduct();

    /** Lanes as Desk's public API allows them; "api" rows become real builders in stage B. */
    private static final List<Capability> MATRIX = Collections.unmodifiableList(Arrays.asList(
        new Capability("field",            "custom field on Tickets, Contacts, Accounts, Tasks (text, textarea, email, phone, url, picklist, multiselect, date, datetime, number, decimal, currency, percent, boolean, lookup)", "api", "POST organizationFields"),
        new Capability("picklist_option",  "new option on an existing picklist",              "api",     "PATCH the field"),
        new Capability("layout_place",     "placing fields in sections of a department's layout", "api",  "Update Layout Field"),
        new Capability("department",       "new department",                                   "ui",      "Setup → Departments (API: Add department — stage B)"),
        new Capability("team",             "team of agents",                                   "api",     "POST teams"),
        new Capability("role",             "agent role",                                       "api",     "POST roles"),
        new Capability("assignment",       "direct or round-robin assignment rule",            "api",     "Direct Assignment Rule / Round Robin Rule APIs"),
        new Capability("sla",              "SLA policy with targets and escalations",          "api",     "Create SLA + Create SLA Target"),
        new Capability("validation_rule",  "layout validation rule (reject a save with a message)", "api", "Create Validation Rule"),
        new Capability("blueprint",        "ticket Blueprint",                                 "api",     "Create Blueprint"),
        new Capability("workflow",         "workflow rule (trigger → field update, alert, task, webhook, custom function), time-based rules", "ui", "no public create API — guided runner in Setup → Automation → Workflows"),
        new Capability("macro",            "macro",                                            "ui",      "no public create API"),
        new Capability("view",             "saved ticket view",                                "ui",      "no public create API"),
        new Capability("notification",     "notification rule / email template",               "ui",      "no public create API"),
        new Capability("function",         "Deluge custom function",                           "ui",      "no public create API")
    ));

    @Override public String id() { return "desk"; }
    @Override public String label() { return "Zoho Desk"; }
    @Override public String entityWord() { return "module"; }
    @Override public List<Capability> capabilities() { return MATRIX; }
    @Override public boolean buildersReady() { return false; }
    @Override public List<String> defaultModules() { return DeskOrg.MODULES; }
    @Override public List<String> oauthScopes() { return Arrays.asList("Desk.basic.READ", "Desk.settings.READ", "Desk.tickets.ALL", "Desk.contacts.READ"); }
    @Override public ImplOrg readOrg(CrmClient client, Collection<String> modules, boolean withLayouts) { return DeskOrg.read(client, modules, withLayouts); }

    @Override
    public String setupRoot(String section) {
        switch (section == null ? "" : section) {
            case "fields": case "layouts": case "validation_rules": return "Setup → Customization → Layouts and Fields";
            case "workflows": return "Setup → Automation";
            case "functions": return "Setup → Developer Space → Functions";
            case "roles": return "Setup → Users and Control → Roles";
            default: return "Setup";
        }
    }

    @Override
    public String automationMenu(String type) {
        switch (type == null ? "" : type) {
            case "time_based":   return "Setup → Automation → Workflows → Rules → New Rule → execute on a time basis.";
            case "assignment":   return "Setup → Automation → Assignment Rules → New Rule (direct or round robin).";
            case "sla":          return "Setup → Automation → SLAs → New SLA → targets and escalations.";
            case "blueprint":    return "Setup → Automation → Blueprints → New Blueprint.";
            case "macro":        return "Setup → Automation → Macros → New Macro.";
            case "view":         return "Tickets → Views → Create View (then share it with the roles named).";
            case "notification": return "Setup → Automation → Notification Rules.";
            case "webhook":      return "Setup → Developer Space → Webhooks.";
            default:             return "Setup → Automation → Workflows → Rules → New Rule.";
        }
    }

    @Override
    public String laneFor(String section, JsonNode o) {
        if ("exists".equals(o.path("status").asText())) return "none";
        switch (section) {
            case "open_questions": return "review";
            case "plan": return "projects";
            case "modules": return "none";            // Desk modules are fixed; departments are a Setup item
            default: return "rpa";                    // stage B turns the api rows of the matrix into builders
        }
    }

    private static final String COMMON_RULES =
          "RULES:\n"
        + "- Only include what this BRD states or clearly implies. No generic best-practice config, no items for "
        + "requirements the live org already meets.\n"
        + "- Reuse what exists: the live org metadata lists departments and the fields of each module with their "
        + "picklist options. Never propose a field that exists. If an existing picklist needs a new option, add it to "
        + "that field's picklist_values and set assumption to \"adds option X to <field>\".\n"
        + "- Decide, don't ask, when Desk has a sensible default (field applies to all departments, text length 255, "
        + "rule on ticket create): build with the default and record it in the item's assumption. Use open_questions "
        + "ONLY for business facts no default can replace (who is notified, thresholds, SLA hours) and set blocking=true "
        + "only when the item cannot be built without the answer.\n"
        + "- Every item sets req_ids to the requirement IDs it delivers.\n"
        + "- Use Zoho Desk terms and api names: modules Tickets, Contacts, Accounts, Tasks; ticket fields such as "
        + "subject, status, priority, channel, departmentId, assigneeId, dueDate, cf_* for custom fields.\n"
        + "STRICT JSON: literal values only, no comments, no markdown fences, no prose outside the object.";

    @Override
    public String systemPrompt(String group) {
        return "automation".equals(group) ? automationSystem() : dataModelSystem();
    }

    private String dataModelSystem() {
        return "You are LiftOff's implementation planner for Zoho Desk. You turn a BRD into build items. In this "
            + "request you cover the DATA MODEL and ACCESS only.\n\n" + capabilityPrompt() + "\n\n"
            + "Return ONE JSON object with these keys (use [] for a section with nothing):\n"
            + "summary (string, one sentence)\n"
            + "modules: [] — Desk's modules are fixed; a new department goes in open_questions only if the BRD needs one\n"
            + "fields: [{module (Tickets|Contacts|Accounts|Tasks), label, api_name (cf_snake_case), data_type (text|textarea|"
            + "email|phone|website|picklist|multiselectpicklist|date|datetime|integer|decimal|currency|percent|boolean|lookup), "
            + "length?, picklist_values (string[]), lookup_module?, required (bool), unique (bool), default_value?, "
            + "tooltip?, departments (string[] — department names, [] = all), assumption?, req_ids}]\n"
            + "layouts: [{module, name (department layout name), sections:[{name, fields:[field labels]}], profiles [], notes, req_ids}] — "
            + "only when the BRD says which section a field belongs in\n"
            + "validation_rules: [{module, name, condition (plain language using field labels), error_message, assumption?, req_ids}] — "
            + "only when a save must be rejected with a message\n"
            + "roles: [{name, reports_to, profile, share_with_peers (bool), description, permissions (string[]), req_ids}] — agent roles; "
            + "a TEAM goes here too with profile \"team\" and description listing its agents\n"
            + "open_questions: [{question, context, blocking (bool), req_ids}]\n\n" + COMMON_RULES;
    }

    private String automationSystem() {
        return "You are LiftOff's implementation planner for Zoho Desk. You turn a BRD into build items. In this "
            + "request you cover AUTOMATION only.\n\n" + capabilityPrompt() + "\n\n"
            + "Return ONE JSON object with these keys (use [] for a section with nothing):\n"
            + "summary (string, one sentence)\n"
            + "workflows: [{type (workflow|time_based|assignment|sla|blueprint|macro|view|notification), module (Tickets|Contacts|Accounts), "
            + "name, req_ids, assumption?, and\n"
            + "  for type \"workflow\" or \"time_based\" the structured form: trigger_type (create|edit|create_or_edit|field_update|"
            + "date_or_datetime), trigger_fields (api names), conditions ([{field (api name), comparator (equal|not_equal|contains|"
            + "greater_than|less_than|is_empty|is_not_empty), value}]), match (and|or), field_updates ([{field, value}]), "
            + "tasks ([{subject, due_in_days, priority}]), webhooks ([{name, url, http_method}]), other_actions (string[] — alerts, "
            + "comments, custom functions); for time_based also details (e.g. \"1 hour after Escalation Status became L1\");\n"
            + "  for \"assignment\": rule_entries ([{conditions, match, assign_to (string[] — agent emails/names or a team name), round_robin (bool)}]), default_assignee;\n"
            + "  for \"sla\": details (targets: first response / resolution hours per priority, business hours, escalation levels in plain words), "
            + "trigger, criteria;\n"
            + "  for every other type: trigger, criteria, actions (string[]), states (string[] — blueprint states in order), details}]\n"
            + "functions: [{name, module, trigger, purpose, arguments:[{name,type}], code (complete Deluge for Zoho Desk — "
            + "zoho.desk.getRecordById / updateRecord, no placeholders), req_ids}] — ONLY for logic no workflow action can express.\n"
            + "open_questions: [{question, context, blocking (bool), req_ids}]\n\n"
            + "Prefer a time-based workflow over a scheduled function; prefer a field update over a function.\n"
            + COMMON_RULES + "\nDeluge code goes in a JSON string: escape quotes and use \\n for line breaks.";
    }
}
