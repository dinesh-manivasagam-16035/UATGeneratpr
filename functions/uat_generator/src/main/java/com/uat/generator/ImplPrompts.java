package com.uat.generator;

/**
 * Prompts for the implementation generator (/implement).
 *
 * Two calls per BRD part — DATA MODEL and AUTOMATION — keep each response small
 * enough for the provider's output limit. A final PLAN call turns the merged spec
 * into a phased implementation plan.
 */
public final class ImplPrompts {

    private ImplPrompts() {}

    private static final String COMMON_RULES =
          "RULES:\n"
        + "- Only include what this BRD states or clearly implies. Do not add generic best-practice config.\n"
        + "- Reuse what already exists: the live org metadata below lists existing modules and fields. "
        + "Never propose a field or module that already exists; if the BRD needs an existing one changed, "
        + "add an open_question saying what must change.\n"
        + "- Never invent business values (picklist options, thresholds, SLAs, owners). If the BRD doesn't "
        + "give them, add an open_question instead and leave the value empty.\n"
        + "- Every item must set req_ids to the requirement IDs (from the list given) it delivers.\n"
        + "- Use Zoho CRM terminology and real standard api names (Leads, Contacts, Accounts, Deals, "
        + "Lead_Source, Owner, ...).\n"
        + "STRICT JSON: literal values only, no comments, no markdown fences, no prose outside the object.";

    public static String dataModelSystem() {
        return "You are a senior Zoho CRM implementation consultant. You turn a BRD into an implementation "
            + "spec a team can build from. In this request you cover the DATA MODEL and ACCESS only.\n\n"
            + "Return ONE JSON object with these keys (use [] for a section with nothing):\n"
            + "summary (string, 1-2 sentences on what this part needs built)\n"
            + "modules: [{singular_label, plural_label, api_name, kind (custom|standard), purpose, req_ids}] — "
            + "list a standard module only when the BRD uses it, with kind \"standard\"\n"
            + "fields: [{module (api name), label, api_name, data_type (text|textarea|email|phone|website|"
            + "picklist|multiselectpicklist|date|datetime|integer|bigint|decimal|currency|percent|boolean|"
            + "lookup|multiselectlookup|autonumber|formula|fileupload), length?, picklist_values (string[]), "
            + "lookup_module?, required (bool), unique (bool), default_value?, tooltip?, req_ids}] — custom fields only\n"
            + "layouts: [{module, name, sections:[{name, fields:[field labels]}], profiles (string[]), notes, req_ids}] — "
            + "only when the BRD describes how the record page is organised or per-profile layouts\n"
            + "validation_rules: [{module, name, condition (plain-language rule using field labels), "
            + "error_message, req_ids}]\n"
            + "roles: [{name, reports_to, profile, share_with_peers (bool), description, "
            + "permissions (string[] — module/action access this role needs), req_ids}]\n"
            + "open_questions: [{question, context (what the BRD says), blocking (bool — true when the item "
            + "cannot be built without the answer), req_ids}]\n\n"
            + COMMON_RULES;
    }

    public static String automationSystem() {
        return "You are a senior Zoho CRM implementation consultant and Deluge developer. You turn a BRD "
            + "into an implementation spec. In this request you cover AUTOMATION and CUSTOM CODE only.\n\n"
            + "Return ONE JSON object with these keys (use [] for a section with nothing):\n"
            + "summary (string, 1-2 sentences)\n"
            + "workflows: [{type (workflow|blueprint|assignment|approval|scoring|escalation|schedule|cadence|"
            + "webhook), module, name, trigger (e.g. \"On create\", \"On edit of Stage\", \"Daily 09:00\"), "
            + "criteria (plain-language conditions using field labels), actions (string[] — field updates, "
            + "email alerts, tasks, function calls, webhooks), states (string[] — blueprint states in order, "
            + "blueprint only), details (transitions, approvers, round-robin pool, etc.), req_ids}]\n"
            + "functions: [{name (snake_case), module, trigger (which workflow/button/schedule calls it), "
            + "purpose, arguments:[{name, type}], code (complete Deluge for a Zoho CRM standalone or "
            + "automation function — use zoho.crm.getRecordById / updateRecord / searchRecords, "
            + "handle missing values, no placeholders like TODO), req_ids}] — only for logic that workflow "
            + "actions alone can't do\n"
            + "open_questions: [{question, context, blocking (bool), req_ids}]\n\n"
            + COMMON_RULES
            + "\nDeluge code goes in a JSON string: escape quotes and use \\n for line breaks.";
    }

    public static String planSystem() {
        return "You are a Zoho CRM delivery lead. Given an implementation spec, write the delivery plan.\n\n"
            + "Return ONE JSON object: {\"plan\": [{phase, title, description, owner_role "
            + "(Consultant|Developer|Admin|QA|Client), effort_hours (number), priority (P0|P1|P2), "
            + "depends_on (string[] — titles of earlier tasks), spec_refs (string[] — ids of the spec items "
            + "this task builds, e.g. FLD-3, WF-1), req_ids}]}\n\n"
            + "Phases, in order, using only those that apply: \"1. Clarify\" (resolve blocking open "
            + "questions), \"2. Data model\" (modules, fields, layouts, validation rules), \"3. Access\" "
            + "(roles, profiles, sharing), \"4. Automation\" (workflows, blueprints, assignment, approvals), "
            + "\"5. Custom code\" (Deluge functions), \"6. Verify & UAT\", \"7. Go-live\".\n"
            + "Group small related items into one task (e.g. all fields of one module). Every spec item id "
            + "must appear in some task's spec_refs. Efforts are realistic for an experienced consultant.\n"
            + "STRICT JSON: literal values only, no prose outside the object.";
    }

    /** User prompt for one (section group, BRD part) call. */
    public static String partPrompt(String modules, String liftedSchema, String outline, String chunkText,
                                    int part, int totalParts, String requirements, String decisions,
                                    String retryNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("Modules in scope: ").append(modules).append("\n\n");
        if (decisions != null && !decisions.isEmpty()) {
            sb.append("Client decisions (answers to earlier open questions — they override the BRD where they ")
              .append("conflict; use them and don't ask these questions again):\n").append(decisions).append("\n\n");
        }
        sb.append(liftedSchema.isEmpty()
                ? "Live org metadata: not available (CRM not connected). Assume a standard Zoho CRM org.\n\n"
                : liftedSchema + "\n\n");
        if (totalParts > 1 && !outline.isEmpty()) {
            sb.append("Outline of the whole BRD (context only):\n").append(outline).append("\n\n")
              .append("This request covers PART ").append(part).append(" of ").append(totalParts)
              .append(". Only include items this part asks for; other parts are handled separately.\n\n");
        }
        sb.append("=== BRD").append(totalParts > 1 ? " — PART " + part + "/" + totalParts : "").append(" ===\n")
          .append(chunkText).append("\n=== END ===\n\n");
        if (!requirements.isEmpty()) {
            sb.append("Requirements in this part:\n").append(requirements).append("\n\n");
        }
        if (retryNote != null && !retryNote.isEmpty()) sb.append("IMPORTANT: ").append(retryNote).append("\n\n");
        sb.append("Return the JSON object and nothing else.");
        return sb.toString();
    }

    public static String planPrompt(String specDigest, String requirements) {
        return "Implementation spec (one line per item: id — what):\n" + specDigest + "\n\n"
            + (requirements.isEmpty() ? "" : "Requirements:\n" + requirements + "\n\n")
            + "Return {\"plan\": [...]} and nothing else.";
    }
}
