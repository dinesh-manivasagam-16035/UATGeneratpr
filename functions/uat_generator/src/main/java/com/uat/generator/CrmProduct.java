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

    private static final String V = "2026-11-13";                       // last check against the API reference
    private static final String D = "https://www.zoho.com/crm/developer/docs/api/v8/";
    private static Capability c(String kind, String need, String lane, String how, String api, String scope, String status, String docSlug) {
        return new Capability(kind, need, lane, how, api, scope, status, docSlug.isEmpty() ? "" : docSlug.startsWith("http") ? docSlug : D + docSlug + ".html", V);
    }

    /**
     * The Zoho CRM capability sheet — every activity an implementation can need, what the API offers,
     * and what LiftOff does with it today. One table drives the agent's brief, the build lane and the
     * exported sheet (/implement/capabilities), so the three can't disagree. Rows marked "planned"
     * have a verified create API that LiftOff doesn't call yet; "verify" rows have no confirmed API page.
     */
    private static final List<Capability> MATRIX = Collections.unmodifiableList(Arrays.asList(
        // ---- data model ----
        c("module",           "custom module",                                                  "api", "created with the profiles that may see it",
            "POST /settings/modules", "ZohoCRM.settings.modules.CREATE", "builds", "create-custom-module-api"),
        c("field",            "custom field: text, textarea, email, phone, website, picklist, multiselectpicklist, date, datetime, integer, bigint, decimal, currency, percent, boolean, lookup", "api", "required fields are then marked mandatory on the layout",
            "POST /settings/fields?module=X", "ZohoCRM.settings.fields.CREATE", "builds", "create-custom-field"),
        c("picklist_option",  "new option on an existing picklist",                             "api", "existing options are kept; added before a rule uses the value",
            "PATCH /settings/fields/{id}?module=X", "ZohoCRM.settings.fields.UPDATE", "builds", "update-custom-fields"),
        c("field_other",      "field of type autonumber, formula, fileupload, multiselectlookup, image, subform", "ui", "no create API for these types",
            "", "", "runner", ""),
        c("global_picklist",  "global picklist (shared option set)",                            "ui", "API exists; builder not written",
            "POST /settings/global_picklists", "ZohoCRM.settings.global_picklist.CREATE", "planned", "create-global-picklists"),
        c("layout_place",     "placing fields into sections of the existing layout (new section allowed), marking a field required", "api", "",
            "PATCH /settings/layouts/{id}?module=X", "ZohoCRM.settings.layouts.UPDATE", "builds", "update-custom-layout"),
        c("layout_design",    "a second layout, per-profile layouts",                            "ui", "designed in Setup → Modules and Fields → Layouts",
            "", "", "runner", ""),
        c("layout_rule",      "layout rule (show/hide/require fields by condition)",            "ui", "no create API",
            "", "", "runner", ""),
        c("validation_rule",  "validation rule (reject a save with a message)",                 "ui", "no create API — prefer a required flag or a rule action when it meets the requirement",
            "", "", "runner", ""),
        c("related_list",     "related list / linking",                                         "ui", "no create API",
            "", "", "runner", ""),
        c("custom_view",      "custom list view",                                               "ui", "create API not confirmed in v8 reference",
            "", "", "verify", ""),
        c("pipeline",         "deal pipeline / stages",                                         "ui", "API exists; builder not written",
            "POST /settings/pipeline?layout_id=X", "ZohoCRM.settings.pipeline.CREATE", "planned", "create-pipeline"),
        c("variable",         "CRM variable (org-level constant)",                              "ui", "API exists; builder not written",
            "POST /settings/variables", "ZohoCRM.settings.variables.CREATE", "planned", "create-variables"),
        c("tag",              "tags on a module",                                               "ui", "create API not confirmed in v8 reference",
            "", "", "verify", ""),
        // ---- access ----
        c("role",             "role in the hierarchy",                                          "api", "module permissions live on the profile → partial when permissions are listed",
            "POST /settings/roles", "ZohoCRM.settings.roles.CREATE", "builds", "create-role"),
        c("profile",          "profile (clone + permission changes)",                           "ui", "API clones a profile and updates permissions; builder not written",
            "POST /settings/profiles/{id}/actions/clone; PATCH permissions", "ZohoCRM.settings.profiles.CREATE", "planned", "create-profile"),
        c("user",             "add a user",                                                     "ui", "API exists; builder not written (invites a real person)",
            "POST /users", "ZohoCRM.users.CREATE", "planned", "add-user"),
        c("territory",        "territory",                                                      "ui", "read-only API",
            "GET /settings/territories", "ZohoCRM.settings.territories.READ", "runner", "territories"),
        c("sharing_rule",     "data sharing rule",                                              "ui", "read-only API",
            "GET /settings/data_sharing/rules", "ZohoCRM.settings.data_sharing.READ", "runner", "data-sharing-rules"),
        // ---- automation ----
        c("rule_field_update","workflow rule on create / edit / field update / delete, with field update, task and webhook actions", "api", "one call per action, then the rule",
            "POST /settings/automation/workflow_rules (+ field_updates, tasks, webhooks)", "ZohoCRM.settings.workflow_rules.CREATE, automation_actions.CREATE", "builds", "config-workflow"),
        c("rule_email",       "workflow rule action: email alert",                              "partial", "the alert API needs an existing email template (templates are read-only) — the template is made in Setup",
            "POST /settings/automation/email_notifications", "ZohoCRM.settings.automation_actions.CREATE", "planned", "create-email-notification"),
        c("rule_function",    "workflow rule action: call a Deluge function",                   "partial", "the rule is built; the function is written in Setup",
            "", "", "partial", ""),
        c("rule_time",        "time-based (date/datetime) trigger",                             "ui", "no create API for scheduled triggers",
            "", "", "runner", ""),
        c("assignment",       "assignment rule (criteria → users or round robin)",              "api", "applies to records entering through web forms, imports and the API",
            "POST /settings/automation/assignment_rules?module=X", "ZohoCRM.settings.assignment_rules.CREATE", "builds", "create-assignment-rules"),
        c("scoring",          "scoring rule",                                                   "ui", "API exists; builder not written",
            "POST /settings/automation/scoring_rules", "ZohoCRM.settings.scoring_rules.CREATE", "planned", "create-scoring-rules"),
        c("blueprint",        "Blueprint (process with states and transitions)",                "ui", "read API only",
            "", "", "runner", ""),
        c("approval",         "approval process",                                               "ui", "no create API",
            "", "", "runner", ""),
        c("cadence",          "cadence / schedule",                                             "ui", "no create API",
            "", "", "runner", ""),
        c("webhook_standalone","webhook used outside a rule",                                   "api", "",
            "POST /settings/automation/webhooks", "ZohoCRM.settings.automation_actions.CREATE", "builds", "create-webhook"),
        c("function",         "Deluge function (standalone, automation, button)",               "ui", "no create API",
            "", "", "runner", ""),
        c("button",           "custom button",                                                  "ui", "no create API",
            "", "", "runner", ""),
        c("email_template",   "email template",                                                 "ui", "read-only API",
            "GET /settings/email_templates", "ZohoCRM.templates.email.READ", "runner", "email-templates"),
        c("inventory_template","inventory / mail-merge template",                               "ui", "no create API",
            "", "", "runner", ""),
        c("web_form",         "web form",                                                       "ui", "no create API",
            "", "", "runner", ""),
        c("wizard",           "wizard / canvas page",                                           "ui", "no create API",
            "", "", "runner", ""),
        // ---- org ----
        c("currency",         "multi-currency / exchange rate",                                 "ui", "create API not confirmed in v8 reference",
            "", "", "verify", ""),
        c("business_hours",   "business hours, holidays, fiscal year",                          "ui", "no create API",
            "", "", "runner", ""),
        c("sandbox_deploy",   "sandbox → production deployment",                                "ui", "done in Setup → Sandbox; LiftOff hands over the promotion package",
            "", "", "runner", "")
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
            + "A workflow rule can only act after a save (update fields, create tasks, call webhooks); it can NEVER reject or "
            + "block a save. A requirement that says a record must not be saved unless… belongs in validation_rules "
            + "(data-model call), not here — do not emit a workflow for it, and do not emit a function for it either.\n"
            + COMMON_RULES
            + "\nDeluge code goes in a JSON string: escape quotes and use \\n for line breaks.";
    }
}
