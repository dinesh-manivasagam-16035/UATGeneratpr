package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Zoho Creator adapter — stage A: connect and read the environment (applications, forms, fields),
 * brief the agent in Creator vocabulary, compare items with what the app already has, then hand every
 * build item to the guided runner.
 *
 * Creator is deliberately runner-only, and not because a builder is missing: its public API (v2.1) is
 * read-only for application structure. There is no endpoint that creates a form, adds a field, makes a
 * report or page, or writes a Deluge workflow — those live in the Creator builder UI. So the honest
 * lane for every Creator activity is "Setup (runner)", and what LiftOff adds is the blueprint, the
 * step-by-step runner instructions, verification by read-back and UAT on records (records DO have an
 * API: ZohoCreator.form.CREATE / report.READ|UPDATE|DELETE).
 *
 * If Zoho ships metadata-write APIs, each row below gains an endpoint and status "planned", exactly
 * as the CRM rows did — see docs/ADDING_A_PRODUCT.md.
 */
public final class CreatorProduct implements Product {

    static final CreatorProduct INSTANCE = new CreatorProduct();

    private static final String V = "2026-11-13";           // last check against the Creator API reference
    private static final String D = "https://www.zoho.com/creator/help/api/v2.1/";

    private static final List<Capability> MATRIX = Collections.unmodifiableList(Arrays.asList(
        new Capability("form",            "form (the Creator equivalent of a module)",                       "ui", "no create API — built in the Creator builder", "", "", "runner", D + "index.html", V),
        new Capability("field",           "field on a form (single line, multi line, email, phone, URL, number, decimal, currency, percent, date, date-time, dropdown, radio, multi select, checkbox, lookup, subform, file upload, formula, auto number)", "ui", "no create API; GET fields reads what exists", "GET /meta/{owner}/{app}/form/{form}/fields (read only)", "ZohoCreator.meta.form.READ", "runner", D + "get-fields.html", V),
        new Capability("field_validation","mandatory, unique, min/max and other field rules",                "ui", "set on the field in the builder", "", "", "runner", "", V),
        new Capability("report",          "report (list, grid, calendar, kanban, pivot, chart)",             "ui", "no create API; reports are read for records", "", "ZohoCreator.report.READ", "runner", "", V),
        new Capability("page",            "page (dashboard, HTML/Deluge snippets, panels)",                  "ui", "no create API", "", "", "runner", "", V),
        new Capability("workflow",        "workflow (on create / edit / delete, on load, scheduled) written in Deluge", "ui", "no create API — Deluge lives in the builder", "", "", "runner", "", V),
        new Capability("validation_rule", "on-validate workflow that rejects a submission with a message",   "ui", "no create API", "", "", "runner", "", V),
        new Capability("schedule",        "scheduled workflow / scheduler",                                  "ui", "no create API", "", "", "runner", "", V),
        new Capability("approval",        "approval flow (Blueprint-style stages on a form)",                "ui", "no create API", "", "", "runner", "", V),
        new Capability("permission",      "permission set / role, field and section permissions",            "ui", "no create API; a permission set can switch the API off entirely", "", "", "runner", D + "things-to-know.html", V),
        new Capability("user_portal",     "portal or customer user access",                                  "ui", "no create API", "", "", "runner", "", V),
        new Capability("integration",     "connection to another service used by Deluge",                    "ui", "no API — authorised interactively", "", "", "runner", "", V),
        new Capability("environment",     "promotion from development to stage to production",               "ui", "no deployment API; the environment header only chooses which one a read or write hits", "", "", "runner", D + "things-to-know.html", V),
        new Capability("record_data",     "records: create, read, update, delete, and bulk export",          "api", "records have a real API — this is what Verify and UAT use", "POST /{owner}/{app}/form/{form}; GET|PATCH|DELETE /{owner}/{app}/report/{report}", "ZohoCreator.form.CREATE, ZohoCreator.report.ALL", "builds", D + "bulk-api/overview.html", V)
    ));

    @Override public String id() { return "creator"; }
    @Override public String label() { return "Zoho Creator"; }
    @Override public String entityWord() { return "form"; }
    @Override public List<Capability> capabilities() { return MATRIX; }
    @Override public boolean buildersReady() { return false; }     // metadata is read-only in Creator's API
    @Override public List<String> defaultModules() { return Collections.emptyList(); }

    @Override public List<String> oauthScopes() {
        return Arrays.asList("ZohoCreator.meta.application.READ", "ZohoCreator.meta.form.READ",
                "ZohoCreator.dashboard.READ", "ZohoCreator.form.CREATE",
                "ZohoCreator.report.READ", "ZohoCreator.report.UPDATE", "ZohoCreator.report.DELETE");
    }

    @Override public ImplOrg readOrg(CrmClient client, Collection<String> forms, boolean withFields) {
        return CreatorOrg.read(client, forms, withFields);
    }

    @Override
    public String setupRoot(String section) {
        switch (section == null ? "" : section) {
            case "fields": case "layouts": return "the app in Creator → Edit → the form";
            case "validation_rules": return "the app in Creator → Edit → the form → Workflows → On Validate";
            case "workflows": case "functions": return "the app in Creator → Edit → Workflows";
            case "roles": return "the app in Creator → Settings → Users and Permissions";
            default: return "the app in Creator → Edit";
        }
    }

    @Override
    public String automationMenu(String type) {
        switch (type == null ? "" : type) {
            case "schedule":   return "Creator → the app → Edit → Workflows → Schedules → New Schedule.";
            case "approval":   return "Creator → the app → Edit → the form → Approval → set the stages.";
            case "blueprint":  return "Creator → the app → Edit → Blueprint → New Blueprint.";
            case "notification": return "Creator → the app → Edit → Workflows → the form's workflow → send an email.";
            default:           return "Creator → the app → Edit → Workflows → New Workflow on the form.";
        }
    }

    @Override
    public String laneFor(String section, JsonNode o) {
        if ("exists".equals(o.path("status").asText())) return "none";
        switch (section) {
            case "open_questions": return "review";
            case "plan": return "projects";
            case "modules": return "rpa";      // a new form is builder work, not an API call
            default: return "rpa";             // Creator's API cannot write application structure
        }
    }

    private static final String COMMON_RULES =
          "RULES:\n"
        + "- Only include what this BRD states or clearly implies. No generic best-practice config, and no "
        + "items for requirements the live app already meets.\n"
        + "- Reuse what exists: the live metadata lists the app's forms and each form's fields with their "
        + "dropdown choices. Never propose a field that exists. If an existing dropdown needs another choice, "
        + "add it to that field's picklist_values and set assumption to \"adds choice X to <field>\".\n"
        + "- Decide, don't ask, when Creator has a sensible default (single line 255 characters, a field is "
        + "optional unless the BRD says otherwise, a workflow runs on create and edit): build with the default "
        + "and record it in the item's assumption. Use open_questions ONLY for business facts no default can "
        + "replace, and set blocking=true only when the item can't be built without the answer.\n"
        + "- Every item sets req_ids to the requirement IDs it delivers.\n"
        + "- Use Creator terms: applications, forms (not modules), fields, reports, pages, workflows in Deluge. "
        + "Field link names are snake_case (Partner_Code).\n"
        + "- Creator's API cannot create forms, fields, reports, pages or workflows: every item is configured in "
        + "the Creator builder, so write each one so a person can follow it step by step. Records are different "
        + "— they have an API, and that is what the UAT run uses.\n"
        + "STRICT JSON: literal values only, no comments, no markdown fences, no prose outside the object.";

    @Override
    public String systemPrompt(String group) {
        return "automation".equals(group) ? automationSystem() : dataModelSystem();
    }

    private String dataModelSystem() {
        return "You are LiftOff's implementation planner for Zoho Creator. You turn a BRD into build items. "
            + "In this request you cover the DATA MODEL and ACCESS only.\n\n" + capabilityPrompt() + "\n\n"
            + "Return ONE JSON object with these keys (use [] for a section with nothing):\n"
            + "summary (string, one sentence)\n"
            + "modules: [{api_name (form link name, snake_case), singular_label, plural_label, kind (\"form\"), "
            + "description, req_ids}] — a new FORM the BRD needs\n"
            + "fields: [{module (form link name), label, api_name (snake_case), data_type (text|textarea|email|phone|"
            + "website|picklist|multiselectpicklist|date|datetime|integer|decimal|currency|percent|boolean|lookup), "
            + "length?, picklist_values (string[] — dropdown choices), lookup_module?, required (bool), unique (bool), "
            + "default_value?, tooltip?, assumption?, req_ids}]\n"
            + "layouts: [{module, name (\"form layout\"), sections:[{name, fields:[field labels]}], profiles [], notes, "
            + "req_ids}] — only when the BRD says which section of the form a field belongs in\n"
            + "validation_rules: [{module, name, condition (plain language using field labels), error_message, "
            + "assumption?, req_ids}] — an on-validate workflow that rejects the submission\n"
            + "roles: [{name, reports_to, profile (permission set name), share_with_peers (bool), description, "
            + "permissions (string[] — \"View <Form>\", \"No Delete <Form>\"), req_ids}]\n"
            + "open_questions: [{question, context, blocking (bool), req_ids}]\n\n" + COMMON_RULES;
    }

    private String automationSystem() {
        return "You are LiftOff's implementation planner for Zoho Creator. You turn a BRD into build items. "
            + "In this request you cover AUTOMATION only.\n\n" + capabilityPrompt() + "\n\n"
            + "Return ONE JSON object with these keys (use [] for a section with nothing):\n"
            + "summary (string, one sentence)\n"
            + "workflows: [{type (workflow|schedule|approval|notification), module (form link name), name, trigger "
            + "(\"on create\", \"on create or edit\", \"on delete\", \"on load\", \"daily at 09:00\"), criteria, "
            + "actions (string[]), details, assumption?, req_ids}]\n"
            + "functions: [{name (snake_case), module, trigger, purpose, arguments:[{name, type}], code (complete "
            + "Deluge for Creator — Form_Name[ID == input.ID], insert into, sendmail — no placeholders), req_ids}] — "
            + "the Deluge a workflow runs. A Creator workflow IS Deluge, so put the logic here and reference it from "
            + "the workflow item rather than describing it twice.\n"
            + "open_questions: [{question, context, blocking (bool), req_ids}]\n\n"
            + COMMON_RULES + "\nDeluge code goes in a JSON string: escape quotes and use \\n for line breaks.";
    }
}
