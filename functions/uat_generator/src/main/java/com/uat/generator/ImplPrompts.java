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

    /** Product whose adapter writes the system prompts (one product per deployment for now). */
    public static final String PRODUCT = System.getenv("IMPL_PRODUCT") == null ? "crm" : System.getenv("IMPL_PRODUCT");

    /** The plan is derived in code from the spec and the capability lanes; set IMPL_PLAN_LLM=1 to ask the agent instead. */
    public static final boolean PLAN_VIA_LLM = "1".equals(System.getenv("IMPL_PLAN_LLM"));

    public static String dataModelSystem() { return Products.byId(PRODUCT).systemPrompt("data_model"); }
    public static String automationSystem() { return Products.byId(PRODUCT).systemPrompt("automation"); }

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
