package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * Turns a failed CRM settings call into something a consultant can act on: the request that was
 * sent, the response that came back, a plain-language cause and the checks to make. Pure string
 * classification — no AI — so it is instant and deterministic.
 */
public final class ImplDiagnose {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplDiagnose() {}

    /** Thrown by a build step when a CRM call fails; carries the request and response for the log. */
    public static class StepFailure extends IllegalStateException {
        public final String method, path;
        public final JsonNode requestBody;
        public final int status;
        public final String responseBody;

        public StepFailure(String message, String method, String path, JsonNode requestBody, int status, String responseBody) {
            super(message);
            this.method = method; this.path = path; this.requestBody = requestBody;
            this.status = status; this.responseBody = responseBody;
        }
    }

    /** Builds the "failure" node for a step result. */
    public static ObjectNode describe(Exception e, String section, JsonNode item) {
        ObjectNode f = MAPPER.createObjectNode();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        f.put("message", ImplementServlet.trim(msg, 600));
        int status = 0;
        String response = "";
        if (e instanceof StepFailure) {
            StepFailure sf = (StepFailure) e;
            status = sf.status;
            response = sf.responseBody == null ? "" : sf.responseBody;
            ObjectNode rq = f.putObject("request");
            rq.put("method", sf.method);
            rq.put("path", sf.path);
            if (sf.requestBody != null) rq.set("body", sf.requestBody);
            ObjectNode rs = f.putObject("response");
            rs.put("status", status);
            try { rs.set("body", MAPPER.readTree(response)); } catch (Exception ignore) { rs.put("body", ImplementServlet.trim(response, 2000)); }
        }
        hint(f, msg + " " + response, section, item, status);
        return f;
    }

    static void hint(ObjectNode f, String text, String section, JsonNode item, int status) {
        String t = text.toLowerCase(Locale.ROOT);
        ArrayNode checks = f.putArray("checks");
        String cause;
        String jsonPath = extract(text, "\"json_path\":\"", "\"");
        String apiName = extract(text, "\"api_name\":\"", "\"");

        if (t.contains("field_updates") && (t.contains("seems to be invalid") || t.contains("invalid_data")) && jsonPath.contains("value")) {
            cause = "The value the rule should write isn't one of the field's picklist options, so CRM rejected the field update.";
            checks.add("Setup → Customization → Modules and Fields → the module → the field: is the value in the picklist exactly as spelled in the blueprint?");
            checks.add("LiftOff now adds a missing picklist value before creating the update — Retry this step.");
            checks.add("If the field isn't a picklist, change the rule's value in Blueprint → Show details → Automation.");
        } else if (t.contains("seems to be invalid") || t.contains("invalid_data")) {
            cause = "CRM rejected one value in the request" + (jsonPath.isEmpty() ? "." : ": " + jsonPath + (apiName.isEmpty() ? "" : " (" + apiName + ")") + ".");
            checks.add("Compare that value in the request body below with the live field or module in the org (type, length, picklist options).");
            checks.add("Edit the item in Blueprint → Show details, then Retry.");
        } else if (t.contains("duplicate_data") || t.contains("already exists") || t.contains("duplicate")) {
            cause = "Something with this name or API name already exists in the org.";
            checks.add("Look it up in Setup; if it is the same thing, Skip this step — Verify will still find it.");
            checks.add("If it is different, rename the item in Blueprint → Show details and Retry.");
        } else if (t.contains("oauth_scope_mismatch") || t.contains("invalid_token") || t.contains("invalid_oauth") || status == 401) {
            cause = "The CRM connection doesn't have the scope this API needs (or the token expired).";
            checks.add("Brief → Disconnect CRM, then Connect again so the new scopes are granted.");
            checks.add("Needed: ZohoCRM.settings.ALL (fields, layouts, automation) and ZohoCRM.modules.ALL (UAT records).");
        } else if (t.contains("no_permission") || t.contains("not allowed") || status == 403) {
            cause = "The connected CRM user isn't allowed to customise this part of the org.";
            checks.add("Setup → Security Control → Profiles → the user's profile → Setup permissions: Customization and Automation must be on.");
            checks.add("Or connect LiftOff as an administrator.");
        } else if (t.contains("invalid_module")) {
            cause = "CRM doesn't know this module by the API name LiftOff used.";
            checks.add("Setup → Developer Space → APIs → API Names: find the module's exact API name.");
            checks.add("Fix the module in Blueprint → Show details, then Retry.");
        } else if (t.contains("mandatory_not_found") || t.contains("required field")) {
            cause = "A field CRM requires for this API wasn't in the request" + (jsonPath.isEmpty() ? "." : ": " + jsonPath + ".");
            checks.add("Check the request body below against the Zoho CRM API reference for this endpoint.");
            checks.add("Report it as a LiftOff bug with the run log — the request builder should supply that field.");
        } else if (t.contains("isn't in") && t.contains("yet")) {
            cause = "This item depends on a field that wasn't created, so the rule can't reference it.";
            checks.add("Scroll up: did the field's step fail or get skipped? Retry that step first, then this one.");
            checks.add("If the field exists under another API name, point the rule at it in Blueprint → Show details.");
        } else if (t.contains("limit") || status == 429) {
            cause = "The CRM API rate limit or an org limit (custom fields, rules) was hit.";
            checks.add("Wait a minute and Retry.");
            checks.add("Setup → Customization → the module: check the custom field / workflow rule count against your edition's limit.");
        } else if (status >= 500 || t.contains("internal_error")) {
            cause = "CRM returned a server-side error; this is usually temporary.";
            checks.add("Retry once. If it repeats, open the request body below and try it in the API console to confirm.");
        } else if (status == 0 && (t.contains("timed out") || t.contains("timeout"))) {
            cause = "The call didn't finish within the time limit.";
            checks.add("Retry — the item may already have been created; Verify will tell you.");
        } else {
            cause = "CRM didn't accept the request.";
            checks.add("Read the response below — Zoho's code and message name the field that was wrong.");
            checks.add("Compare the request body with the live org in Setup, fix the item in Blueprint → Show details, then Retry.");
        }
        f.put("cause", cause);
        f.put("api_docs", docsFor(section));
    }

    static String docsFor(String section) {
        switch (section == null ? "" : section) {
            case "fields": return "Zoho CRM API → Fields Meta Data → Create Custom Fields";
            case "workflows": return "Zoho CRM API → Workflow Rules / Field Updates / Tasks (settings/automation)";
            case "layouts": return "Zoho CRM API → Layouts → Update Custom Layout";
            case "modules": return "Zoho CRM API → Modules → Create Custom Module";
            case "roles": return "Zoho CRM API → Roles";
            default: return "Zoho CRM API reference";
        }
    }

    static String extract(String s, String start, String end) {
        if (s == null) return "";
        int i = s.indexOf(start);
        if (i < 0) return "";
        int j = s.indexOf(end, i + start.length());
        return j < 0 ? "" : s.substring(i + start.length(), j);
    }
}
