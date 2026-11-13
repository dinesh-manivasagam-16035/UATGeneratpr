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

    private static final String V = "2026-11-13";                       // last check against the API reference (full v8 api-references index walked)
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
        c("layout_activate",  "activate / deactivate a layout",                                 "ui", "API exists; builder not written",
            "POST /settings/layouts/{id}/actions/activate?module=X", "ZohoCRM.settings.layouts.CREATE", "planned", "activate-custom-layout"),
        c("layout_rule",      "layout rule (show/hide/require fields by condition)",            "ui", "no create API",
            "", "", "runner", ""),
        c("validation_rule",  "validation rule (reject a save with a message)",                 "ui", "no create API — prefer a required flag or a rule action when it meets the requirement",
            "", "", "runner", ""),
        c("related_list",     "related list / linking",                                         "ui", "no create API",
            "", "", "runner", ""),
        c("custom_view",      "custom list view",                                               "ui", "no create API (v8 has get + sort order only)",
            "", "", "runner", ""),
        c("pipeline",         "deal pipeline / stages",                                         "ui", "API exists; builder not written",
            "POST /settings/pipeline?layout_id=X", "ZohoCRM.settings.pipeline.CREATE", "planned", "create-pipeline"),
        c("variable",         "CRM variable (org-level constant)",                              "ui", "API exists; builder not written",
            "POST /settings/variables", "ZohoCRM.settings.variables.CREATE", "planned", "create-variables"),
        c("tag",              "tags on a module",                                               "ui", "API exists; builder not written",
            "POST /settings/tags?module=X", "ZohoCRM.settings.tags.CREATE", "planned", "create-tag"),
        c("map_dependency",   "picklist dependency (parent option → allowed child options)",    "ui", "API exists; builder not written",
            "POST /settings/layouts/{layout_id}/map_dependency?module=X", "ZohoCRM.settings.map_dependency.CREATE", "planned", "map-dependency"),
        c("module_settings",  "rename / show / hide a module, module settings",                 "ui", "API exists; builder not written",
            "PUT /settings/modules/{api_name}", "ZohoCRM.settings.modules.UPDATE", "planned", "update-custom-module-api"),
        c("subform",          "subform (line items) on a module",                                "ui", "no create API",
            "", "", "runner", ""),
        c("unique_field",     "duplicate check: unique field / dedupe preference",              "api", "unique flag is set on the field; the module-level preference API is not called yet",
            "PATCH /settings/fields/{id} (unique); POST /settings/duplicate_check_preference?module=X", "ZohoCRM.settings.fields.UPDATE", "builds", "enable-duplicate-record-check"),
        c("record_locking",   "record locking configuration",                                   "ui", "API exists; builder not written",
            "POST /settings/record_locking_configurations?module=X", "ZohoCRM.settings.record_locking_configurations.CREATE", "planned", "add-record-locking-config"),
        // ---- access ----
        c("role",             "role in the hierarchy",                                          "api", "module permissions live on the profile → partial when permissions are listed",
            "POST /settings/roles", "ZohoCRM.settings.roles.CREATE", "builds", "create-role"),
        c("profile",          "profile (clone + permission changes)",                           "ui", "API clones a profile and updates permissions; builder not written",
            "POST /settings/profiles/{id}/actions/clone; PATCH permissions", "ZohoCRM.settings.profiles.CREATE", "planned", "create-profile"),
        c("user",             "add a user",                                                     "ui", "API exists; builder not written (invites a real person)",
            "POST /users", "ZohoCRM.users.CREATE", "planned", "add-user"),
        c("territory",        "territory",                                                      "ui", "API exists; builder not written",
            "POST /settings/territories", "ZohoCRM.settings.territories.CREATE", "planned", "add-territories"),
        c("sharing_rule",     "data sharing rule",                                              "ui", "API exists; builder not written",
            "POST /settings/data_sharing/rules?module=X", "ZohoCRM.settings.data_sharing.CREATE", "planned", "create-data-sharing-rules"),
        // ---- automation ----
        c("rule_field_update","workflow rule on create / edit / field update / delete, with field update, task and webhook actions", "api", "one call per action, then the rule",
            "POST /settings/automation/workflow_rules (+ field_updates, tasks, webhooks)", "ZohoCRM.settings.workflow_rules.CREATE, automation_actions.CREATE", "builds", "config-workflow"),
        c("rule_email",       "workflow rule action: email alert",                              "partial", "the alert API needs an existing email template (templates are read-only) — the template is made in Setup",
            "POST /settings/automation/email_notifications", "ZohoCRM.settings.automation_actions.CREATE", "planned", "create-email-notification"),
        c("rule_function",    "workflow rule action: call a Deluge function",                   "partial", "the rule and the function are both built by API; attaching the function to the rule is the Setup step (no function action in the workflow API)",
            "", "", "partial", ""),
        c("rule_time",        "time-based (date/datetime) trigger and delayed (scheduled) actions", "ui", "the workflow API takes execute_when type date_or_datetime and scheduled_actions; builder not written",
            "POST /settings/automation/workflow_rules (execute_when.date_or_datetime, scheduled_actions)", "ZohoCRM.settings.workflow_rules.CREATE", "planned", "config-workflow"),
        c("assignment",       "assignment rule (criteria → users or round robin)",              "api", "applies to records entering through web forms, imports and the API",
            "POST /settings/automation/assignment_rules?module=X", "ZohoCRM.settings.assignment_rules.CREATE", "builds", "create-assignment-rules"),
        c("scoring",          "scoring rule",                                                   "ui", "API exists; builder not written",
            "POST /settings/automation/scoring_rules", "ZohoCRM.settings.scoring_rules.CREATE", "planned", "create-scoring-rules"),
        c("assignment_threshold","assignment threshold (max open records per user)",               "ui", "API exists; builder not written",
            "POST /settings/automation/assignment_thresholds?module=X", "ZohoCRM.settings.assignment_thresholds.CREATE", "planned", "create-assignment-threshold"),
        c("connected_workflow","connected workflow (cross-module rules)",                        "ui", "API exists; builder not written",
            "POST /settings/connected_workflows", "ZohoCRM.settings.connected_workflows.CREATE", "planned", "create-connected-workflow"),
        c("blueprint",        "Blueprint (process with states and transitions)",                "partial", "created in Draft with states mapped to the picklist, transitions and chart; LiftOff then calls activate — when Zoho refuses (chart or transition check) the Draft is published in Setup",
            "POST /settings/blueprints; POST …/{id}/actions/activate", "ZohoCRM.settings.blueprint.CREATE", "partial", "create-blueprint"),
        c("approval",         "approval process",                                               "ui", "no create API",
            "", "", "runner", ""),
        c("cadence",          "cadence (sequence of emails/tasks/calls)",                       "ui", "API creates and publishes cadences; builder not written",
            "POST /settings/automation/cadences", "ZohoCRM.settings.cadences.CREATE", "planned", "create-cadences"),
        c("webhook_standalone","webhook used outside a rule",                                   "api", "",
            "POST /settings/automation/webhooks", "ZohoCRM.settings.automation_actions.CREATE", "builds", "create-webhook"),
        c("function",         "Deluge function (automation, button, schedule, standalone)",      "api", "created and published with its code; LiftOff writes the declaration from the arguments. A rule that calls it is linked in Setup (the workflow API has no function action)",
            "POST /settings/functions (multipart metadata, _code)", "ZohoCRM.settings.functions.CREATE", "builds", "create-function"),
        c("button",           "custom button",                                                  "ui", "no create API",
            "", "", "runner", ""),
        c("email_template",   "email template",                                                 "ui", "read-only API",
            "GET /settings/email_templates", "ZohoCRM.templates.email.READ", "runner", "email-templates"),
        c("inventory_template","inventory / mail-merge template",                               "ui", "no create API",
            "", "", "runner", ""),
        c("web_form",         "web form",                                                       "ui", "API exists; builder not written",
            "POST /settings/webforms?module=X", "ZohoCRM.settings.webforms.CREATE", "planned", "create-webform"),
        c("webform_ab",       "web form A/B test",                                              "ui", "API exists; builder not written",
            "POST /settings/AB_testing?module=X", "ZohoCRM.settings.abtest.CREATE", "planned", "create-webform-ab-testing"),
        c("kiosk",            "Kiosk Studio screen flow",                                       "ui", "API exists; builder not written",
            "POST /settings/kiosks", "ZohoCRM.settings.kiosks.CREATE", "planned", "kiosk-creation"),
        c("wizard",           "wizard / canvas page",                                           "ui", "no create API",
            "", "", "runner", ""),
        // ---- org ----
        c("currency",         "multi-currency / exchange rate",                                 "ui", "API enables multi-currency and adds currencies; builder not written",
            "POST /org/currencies/actions/enable; POST /org/currencies", "ZohoCRM.settings.currencies.CREATE", "planned", "add-currencies"),
        c("business_hours",   "business hours, shift hours, holidays, fiscal year",             "ui", "APIs exist; builder not written",
            "POST /settings/business_hours; POST /settings/holidays; PUT /settings/fiscal_year", "ZohoCRM.settings.business_hours.CREATE", "planned", "add-business-hours"),
        c("user_unavailability","user unavailability (leave) for assignment",                     "ui", "API exists; builder not written",
            "POST /settings/users_unavailability", "ZohoCRM.settings.users_unavailability.WRITE", "planned", "post-user-unavailability"),
        c("company_details",  "company details, logo, locale, time zone",                       "ui", "no update-org API (logo upload only)",
            "", "", "runner", ""),
        c("group",            "user group",                                                     "ui", "API exists; builder not written",
            "POST /settings/user_groups", "ZohoCRM.settings.user_groups.CREATE", "planned", "create-user-group"),
        c("field_permission", "field-level and module-level permissions per profile",           "ui", "API exists; builder not written",
            "PUT /settings/profiles/{id}", "ZohoCRM.settings.profiles.UPDATE", "planned", "update-profile-permission"),
        c("portal",           "client portal (configuration)",                                  "ui", "API exists; builder not written (users are invited in Setup)",
            "POST /settings/portals", "ZohoCRM.settings.clientportal.CREATE", "planned", "create-portal"),
        c("sandbox_deploy",   "sandbox → production deployment",                                "ui", "done in Setup → Sandbox; LiftOff hands over the promotion package",
            "", "", "runner", ""),
        // ---- process & channels ----
        c("review_process",   "review process / command center journey",                        "ui", "no create API",
            "", "", "runner", ""),
        c("escalation_rule",  "case escalation rule",                                           "ui", "no create API",
            "", "", "runner", ""),
        c("auto_response",    "web form auto-response rule / email parser",                     "ui", "no create API",
            "", "", "runner", ""),
        c("email_setup",      "email (IMAP/POP) configuration, org email addresses, SalesInbox", "ui", "no API — each user connects their own mailbox",
            "", "", "runner", ""),
        c("unsubscribe_link", "email unsubscribe link",                                         "ui", "API exists; builder not written",
            "POST /settings/unsubscribe_links", "ZohoCRM.settings.unsubscribe.CREATE", "planned", "create-unsubscribe-link"),
        c("telephony_social", "telephony, social and chat channels",                            "ui", "no API — marketplace integration enabled in Setup",
            "", "", "runner", ""),
        c("calls_preferences","calls module preferences",                                       "ui", "API exists; builder not written",
            "PUT /settings/call_preferences", "ZohoCRM.settings.modules.UPDATE", "planned", "put-calls-preferences"),
        c("services_appointments","Services & Appointments setup (services catalogue, appointment preferences)","ui", "APIs exist; builder not written",
            "POST /Services__s; PUT /settings/appointment_preferences", "ZohoCRM.modules.services.CREATE", "planned", "create-services"),
        c("signals_feeds",    "Signals, feeds, notification preferences",                       "ui", "no API",
            "", "", "runner", ""),
        // ---- data ----
        c("data_import",      "data import / migration of records",                             "ui", "CSV → Bulk Write job per module; LiftOff doesn't run imports today (UAT seeds records one by one)",
            "POST /bulk/v8/write (+ upload)", "ZohoCRM.bulk.CREATE", "planned", "bulk-write/create-job"),
        c("data_export",      "data export / backup schedule",                                  "ui", "Bulk Read exports, backup API schedules; builder not written",
            "POST /bulk/v8/read; POST /bulk/v8/backup", "ZohoCRM.bulk.read, bulk.backup.CREATE", "planned", "schedule-data-backup"),
        c("mass_update",      "mass update / transfer of existing records",                     "ui", "records API in batches of 100",
            "PUT /{module}", "ZohoCRM.modules.ALL", "planned", "update-records"),
        c("recycle_audit",    "recycle bin, audit log, storage, sample data removal",           "ui", "read APIs only (audit log via Bulk Read)",
            "", "", "runner", ""),
        // ---- reporting ----
        c("report",           "report (tabular / summary / matrix)",                            "ui", "no create API",
            "", "", "runner", ""),
        c("dashboard",        "analytics dashboard and components",                             "ui", "no create API",
            "", "", "runner", ""),
        c("home_page",        "home page / tab group customization",                            "ui", "no API",
            "", "", "runner", ""),
        c("calendar_view",    "calendar view / calendar preferences",                           "ui", "API exists; builder not written",
            "POST /settings/calendar", "ZohoCRM.settings.calendar.CREATE", "planned", "create-calendar-view"),
        // ---- developer & integrations ----
        c("connection",       "Connection (OAuth link used by Deluge)",                         "ui", "no API — authorised interactively",
            "", "", "runner", ""),
        c("widget_client_script","widget, client script",                                          "ui", "no create API",
            "", "", "runner", ""),
        c("notification_channel","API change notifications (watch) for integrations",              "ui", "API exists; not an implementation item for LiftOff",
            "POST /actions/watch", "ZohoCRM.notifications.CREATE", "planned", "notifications/enable"),
        c("marketplace",      "marketplace extension / Zoho product integration (Books, Desk, Campaigns, Analytics)", "ui", "no API — installed and mapped in Setup",
            "", "", "runner", ""),
        c("zia",              "Zia settings (prediction, enrichment, anomaly)",                 "ui", "data-enrichment config has an API; the rest is Setup",
            "POST /settings/zia/data_enrichment", "ZohoCRM.Zia.enrichment.ALL", "runner", "zia-enrichment/create-data-enrichment-configuration"),
        c("translations",     "translations / copy customization to another org",               "ui", "no API",
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
                if ("blueprint".equals(type)) {
                    // Draft created by API with states and transitions; publishing may still be a Setup click.
                    return ImplBlueprint.states(o).size() >= 2 ? "partial" : "rpa";
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
            case "functions": return "api";          // POST settings/functions publishes Deluge with its code
            case "open_questions": return "review";
            case "plan":    return "projects";
            default:        return "rpa";   // validation rules
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
            + "  for \"blueprint\": field (api name of the picklist the states map to — Lead_Status, Stage, or a picklist "
            + "from the data model), states (string[] in order, each a value of that picklist — reuse the org's existing "
            + "options, add new ones only when the BRD names them), transitions ([{name, from, to}] — omit for a straight "
            + "chain), criteria (plain words: which records enter), details;\n"
            + "  for every other type: trigger, criteria, actions (string[]), details}]\n"
            + "functions: [{name (snake_case), module, trigger (\"automation: on create/edit of <module>\", \"button on "
            + "<module>\", \"schedule daily\", \"standalone\"), purpose, arguments:[{name, type (int|string|decimal|boolean|"
            + "date|datetime|list|map)}] — an automation function takes exactly one argument, the record id (int), e.g. "
            + "{name:\"leadId\", type:\"int\"}; code (the function BODY only — complete Deluge statements that use the "
            + "arguments by name, no declaration line, no placeholders; LiftOff adds the declaration and publishes it), "
            + "req_ids}] — ONLY for logic that no workflow action can express (loops, cross-module look-ups, arithmetic). "
            + "A function that would do what a workflow item already does is NOT allowed.\n"
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
