package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Zoho CRM adapter: the capability matrix (one table drives both the agent's brief and the build
 * lane, so they can't disagree), the lane rule per spec item, and the blueprint prompts.
 */
public final class CrmProduct implements Product {

    static final CrmProduct INSTANCE = new CrmProduct();

    private static final List<Capability> MATRIX = Collections.unmodifiableList(Arrays.asList(
        new Capability("module",           "custom module",                                   "api",     "POST settings/modules"),
        new Capability("field",            "custom field of type text, textarea, email, phone, website, picklist, multiselectpicklist, date, datetime, integer, bigint, decimal, currency, percent, boolean or lookup", "api", "POST settings/fields; a required field is marked mandatory on the layout"),
        new Capability("picklist_option",  "new option on an existing picklist",              "api",     "PATCH settings/fields — existing options are kept"),
        new Capability("field_other",      "field of type autonumber, formula, fileupload or multiselectlookup", "ui", "no create API"),
        new Capability("layout_place",     "placing fields into sections of the existing layout (new section allowed) / making a field required", "api", "PATCH settings/layouts"),
        new Capability("layout_design",    "a second layout or per-profile layouts",           "ui",      "designed in Setup"),
        new Capability("role",             "role in the hierarchy",                            "api",     "POST settings/roles; module permissions live on the profile → partial when permissions are listed"),
        new Capability("rule_field_update","workflow rule on create / edit / field update / delete with field update, task and webhook actions", "api", "settings/automation/field_updates + tasks + webhooks + workflow_rules"),
        new Capability("rule_other_action","workflow rule action: email alert, function call",  "partial", "the rule is built; the template or function is attached in Setup"),
        new Capability("assignment",       "assignment rule (criteria → users or round robin)", "api",     "POST settings/automation/assignment_rules; applies to records entering through web forms, imports and the API"),
        new Capability("rule_time",        "time-based (date/datetime) trigger",               "ui",      "no create API for scheduled triggers"),
        new Capability("validation_rule",  "validation rule (reject a save with a message)",   "ui",      "no create API — prefer a required flag or a workflow field update when it meets the requirement"),
        new Capability("blueprint",        "Blueprint, approval process, scoring rule, cadence", "ui", "guided runner"),
        new Capability("function",         "Deluge function",                                  "ui",      "no create API — only when no rule action can do it")
    ));

    @Override public String id() { return "crm"; }
    @Override public String label() { return "Zoho CRM"; }
    @Override public String entityWord() { return "module"; }
    @Override public List<Capability> capabilities() { return MATRIX; }
    @Override public ImplOrg readOrg(CrmClient client, java.util.Collection<String> modules, boolean withLayouts) { return ImplOrg.read(client, modules, withLayouts); }

    @Override public List<String> oauthScopes() {
        return Arrays.asList("ZohoCRM.modules.ALL", "ZohoCRM.settings.ALL", "ZohoCRM.org.READ");
    }

    @Override
    public String laneFor(String section, JsonNode o) {
        if ("exists".equals(o.path("status").asText())) return "none";
        switch (section) {
            case "modules": return "standard".equals(o.path("kind").asText()) ? "none" : "api";
            case "fields":  return ImplSpec.API_FIELD_TYPES.contains(o.path("data_type").asText()) ? "api" : "rpa";
            case "roles":   return o.path("permissions").size() > 0 ? "partial" : "api";
            case "workflows": {
                String type = o.path("type").asText();
                if ("assignment".equals(type)) {
                    boolean ok = o.path("rule_entries").size() > 0;
                    for (JsonNode e : o.path("rule_entries")) if (e.path("conditions").size() == 0 || e.path("assign_to").size() == 0) ok = false;
                    return ok ? "api" : "rpa";
                }
                boolean apiRule = "workflow".equals(type)
                        && ImplSpec.API_TRIGGERS.contains(o.path("trigger_type").asText(""))
                        && (o.path("field_updates").size() > 0 || o.path("tasks").size() > 0 || o.path("webhooks").size() > 0);
                if (!apiRule) return "rpa";
                return o.path("other_actions").size() > 0 ? "partial" : "api";
            }
            case "layouts": {
                // Placing fields into sections of an existing layout is an API call; per-profile layouts are not.
                boolean hasFields = false;
                for (JsonNode sct : o.path("sections")) if (sct.path("fields").size() > 0) hasFields = true;
                return hasFields && o.path("profiles").size() == 0 ? "api" : "rpa";
            }
            case "open_questions": return "review";
            case "plan":    return "projects";
            default:        return "rpa";   // validation rules, functions
        }
    }

    // ---- prompts ----

    private static final String COMMON_RULES =
          "RULES:\n"
        + "- Only include what this BRD states or clearly implies. No generic best-practice config, no items "
        + "for requirements that are already met by the live org.\n"
        + "- Reuse what exists: the live org metadata lists modules, fields and picklist options. Never propose "
        + "a field or module that already exists. If an existing picklist needs a new option, do NOT ask — add "
        + "the option to that field's picklist_values and set assumption to \"adds option X to <field>\".\n"
        + "- Decide, don't ask, when Zoho has a sensible default (text length 255, optional field, standard layout, "
        + "rule on create): build with the default and record it in the item's assumption (one short sentence). "
        + "Use open_questions ONLY for business facts the BRD leaves out that no default can replace (thresholds, "
        + "owners, SLAs, which of two meanings) — and mark blocking=true only when the item cannot be built at "
        + "all without the answer.\n"
        + "- Every item sets req_ids to the requirement IDs (from the list given) it delivers.\n"
        + "- Use real Zoho CRM api names (Leads, Contacts, Accounts, Deals, Lead_Status, Lead_Source, Owner, ...).\n"
        + "STRICT JSON: literal values only, no comments, no markdown fences, no prose outside the object.";

    @Override
    public String systemPrompt(String group) {
        return "automation".equals(group) ? automationSystem() : dataModelSystem();
    }

    private String dataModelSystem() {
        return "You are LiftOff's implementation planner for Zoho CRM. You turn a BRD into build items that "
            + "LiftOff creates through the CRM API, so prefer constructs the API can build. In this request you "
            + "cover the DATA MODEL and ACCESS only.\n\n"
            + capabilityPrompt() + "\n\n"
            + "Return ONE JSON object with these keys (use [] for a section with nothing):\n"
            + "summary (string, one sentence on what this part needs built)\n"
            + "modules: [{singular_label, plural_label, api_name, kind (custom|standard), purpose, assumption?, req_ids}] — "
            + "list a standard module only when the BRD uses it, with kind \"standard\"; never system modules\n"
            + "fields: [{module (api name), label, api_name, data_type (text|textarea|email|phone|website|picklist|"
            + "multiselectpicklist|date|datetime|integer|bigint|decimal|currency|percent|boolean|lookup), length?, "
            + "picklist_values (string[] — for a NEW picklist all its options; for an EXISTING picklist only the "
            + "options to add), lookup_module?, required (bool), unique (bool), default_value?, tooltip?, "
            + "assumption?, req_ids}]\n"
            + "layouts: [{module, name, sections:[{name, fields:[field labels]}], profiles (string[]), notes, req_ids}] — "
            + "when the BRD says which section a field belongs in (fields = labels of fields in this spec or the org; "
            + "an unknown section name creates it); a new field with no stated section needs no layout item\n"
            + "validation_rules: [{module, name, condition (plain-language using field labels), error_message, "
            + "assumption?, req_ids}] — ONLY when the BRD explicitly requires a save to be rejected with a message; "
            + "if a required flag or a workflow field update meets the requirement, use that instead\n"
            + "roles: [{name, reports_to, profile, share_with_peers (bool), description, permissions (string[]), req_ids}]\n"
            + "open_questions: [{question, context (what the BRD says), blocking (bool), req_ids}]\n\n"
            + COMMON_RULES;
    }

    private String automationSystem() {
        return "You are LiftOff's implementation planner for Zoho CRM. You turn a BRD into build items that "
            + "LiftOff creates through the CRM API, so prefer constructs the API can build. In this request you "
            + "cover AUTOMATION only.\n\n"
            + capabilityPrompt() + "\n\n"
            + "Return ONE JSON object with these keys (use [] for a section with nothing):\n"
            + "summary (string, one sentence)\n"
            + "workflows: [{type (workflow|blueprint|assignment|approval|scoring|escalation|schedule|cadence|webhook), "
            + "module (api name), name, req_ids, assumption?, and\n"
            + "  for type \"workflow\" ONLY the structured form (no prose duplicate): "
            + "trigger_type (create|edit|create_or_edit|field_update|delete|date_or_datetime), "
            + "trigger_fields (field api names, for field_update), "
            + "conditions ([{field (api name), comparator (equal|not_equal|contains|not_contains|starts_with|"
            + "ends_with|greater_than|less_than|greater_equal|less_equal|is_empty|is_not_empty), value}] — [] for all records), "
            + "match (and|or), field_updates ([{field (api name), value}]), "
            + "tasks ([{subject, due_in_days (integer), priority (Highest|High|Normal|Low|Lowest)}]), "
            + "webhooks ([{name, url, http_method (GET|POST), description?}] — only with a URL the BRD gives), "
            + "other_actions (string[] — email alerts and function calls only, each in a few words);\n"
            + "  for type \"assignment\" the structured form: rule_entries ([{conditions (as above), match, "
            + "assign_to (string[] — user emails or full names from the BRD), round_robin (bool)}]), "
            + "default_assignee (email, full name, or \"current user\");\n"
            + "  for every other type: trigger, criteria, actions (string[]), states (string[] — blueprint states in order), details}]\n"
            + "functions: [{name (snake_case), module, trigger, purpose, arguments:[{name, type}], code (complete Deluge, "
            + "no placeholders), req_ids}] — ONLY for logic that no workflow action can express (loops, cross-module "
            + "look-ups, arithmetic). A function that would do what a workflow item already does is NOT allowed.\n"
            + "open_questions: [{question, context, blocking (bool), req_ids}]\n\n"
            + "A rule that sets a picklist field to a value the org doesn't have is fine: LiftOff adds the option first. "
            + "Record it in assumption.\n"
            + COMMON_RULES
            + "\nDeluge code goes in a JSON string: escape quotes and use \\n for line breaks.";
    }
}
