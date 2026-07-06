package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

/**
 * LLM client using GitHub Models API (OpenAI-compatible).
 *
 * Required env var (set in Catalyst function environment):
 *   GITHUB_TOKEN  — GitHub PAT (from github.com/settings/tokens)
 *
 * Optional:
 *   COPILOT_MODEL    — model ID (default: gpt-4o). Set to exact model ID
 *                      from github.com/marketplace/models, e.g. "gpt-4.1"
 *   COPILOT_ENDPOINT — override the inference endpoint
 */
public final class ClaudeClient {

    private static final ObjectMapper MAPPER           = new ObjectMapper();
    private static final String       DEFAULT_ENDPOINT = "https://models.inference.ai.azure.com/chat/completions";
    private static final String       DEFAULT_MODEL    = "gpt-4o";

    private ClaudeClient() {}

    public static String generate(String brd, String module, String moduleSchema) throws Exception {
        String token    = requireToken();
        String model    = model();
        String endpoint = endpoint();

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", 4096);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        messages.addObject().put("role", "user").put("content", buildUserPrompt(safeBrd(brd), module, moduleSchema));

        return callOpenAICompat(token, endpoint, payload);
    }

    public static String generateForCreator(String brd, String formName,
                                            String owner, String app) throws Exception {
        String token    = requireToken();
        String model    = model();
        String endpoint = endpoint();

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", 4096);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", creatorSystemPrompt(owner, app));
        messages.addObject().put("role", "user").put("content",
                buildCreatorUserPrompt(safeBrd(brd), formName, owner, app));

        return callOpenAICompat(token, endpoint, payload);
    }

    public static String analyze(String brd, String moduleNames) throws Exception {
        String token    = requireToken();
        String model    = model();
        String endpoint = endpoint();

        String sysPrompt =
            "You are a Zoho CRM expert. You will receive a UAT specification document and a "
            + "list of CRM module api_names available in the org. "
            + "Your job: identify which modules (up to 3) the spec is primarily testing, "
            + "and write a concise 1–2 sentence analysis of what the document covers. "
            + "Output ONLY a JSON object with exactly two keys: "
            + "\"suggested_modules\" (array of api_name strings, up to 3, from the provided list) "
            + "and \"analysis\" (string). No markdown, no prose outside the JSON.";

        String userPrompt =
            "Available modules: " + moduleNames + "\n\n"
            + "UAT spec document:\n" + brd + "\n\n"
            + "Return JSON only: {\"suggested_modules\":[...],\"analysis\":\"...\"}";

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", 512);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", sysPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        return callOpenAICompat(token, endpoint, payload);
    }

    // ---- Helpers ----

    private static String requireToken() {
        String t = System.getenv("GITHUB_TOKEN");
        if (t == null || t.isEmpty()) {
            throw new IllegalStateException(
                "GITHUB_TOKEN env var is not set. Add your GitHub PAT to Catalyst function env vars.");
        }
        return t;
    }

    private static String model() {
        String m = System.getenv("COPILOT_MODEL");
        // Ignore legacy Claude model names — this endpoint uses GPT models.
        if (m == null || m.isEmpty() || m.toLowerCase().startsWith("claude")) return DEFAULT_MODEL;
        return m;
    }

    // gpt-4o has a 128k token context window — allow a generous BRD so the model
    // can do deep analysis. System prompt ~400 + schema ~300 + instructions ~300
    // + BRD ~3000 tokens (12 000 chars) + 4096 output ≈ 8100 tokens — well within limits.
    private static String safeBrd(String brd) {
        int limit = 12000; // ~3000 tokens
        return brd.length() > limit ? brd.substring(0, limit) + "\n...[truncated]" : brd;
    }

    private static String endpoint() {
        String e = System.getenv("COPILOT_ENDPOINT");
        return (e != null && !e.isEmpty()) ? e : DEFAULT_ENDPOINT;
    }

    private static String callOpenAICompat(String token, String endpoint, ObjectNode payload) throws Exception {
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(endpoint);
            post.setHeader("Authorization", "Bearer " + token);
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(payload), ContentType.APPLICATION_JSON));

            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                int code = response.getCode();
                if (code >= 400) {
                    throw new RuntimeException("GitHub Models API error " + code + ": " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                return content.isMissingNode() ? body : content.asText();
            });
        }
    }

    private static String systemPrompt() {
        return "You are a senior QA engineer at Zoho specializing in UAT for Zoho CRM implementations. "
            + "Your job is to produce test cases that are DEEPLY SPECIFIC to the provided specification — "
            + "not generic CRM tests that could apply to any org.\n\n"
            + "PHASE 1 — ANALYZE THE BRD (internalize before writing any test case):\n"
            + "a) Identify every module referenced. Note which are standard Zoho CRM modules "
            + "(Leads, Contacts, Deals, Accounts, etc.) and which are CUSTOM modules.\n"
            + "b) List every field mentioned per module. Note which fields exist in the standard schema "
            + "and which are CUSTOM fields unique to this implementation.\n"
            + "c) Extract every USE CASE and business process described — give each a short name "
            + "that you will reuse as spec_ref.\n"
            + "d) Extract every VALIDATION RULE: required fields, field formats, allowed picklist values, "
            + "length limits, and cross-field constraints.\n"
            + "e) Extract every BUSINESS RULE: conditional logic, automation triggers, role/permission "
            + "requirements, SLA rules, score thresholds, stage transitions, etc.\n"
            + "f) Note every integration point, lookup relationship, and data dependency.\n\n"
            + "PHASE 2 — GENERATE TEST CASES (one test per distinct BRD scenario):\n"
            + "- For EVERY use case identified in Phase 1, generate its primary success-path test.\n"
            + "- For EVERY validation rule, generate a test that submits data violating that rule "
            + "and asserts the correct rejection.\n"
            + "- For EVERY business rule and conditional branch, generate a test that exercises it.\n"
            + "- For EVERY custom field or custom module, generate tests that verify its BRD-defined "
            + "behaviour (not just that it exists).\n"
            + "- Workflow triggers, automations, and stage transitions each need at least one test.\n\n"
            + "QUALITY GATE — discard any test case where:\n"
            + "- It could be written without reading this specific BRD (generic CRUD test)\n"
            + "- The spec_ref cannot be matched to a named use case, section, or rule in the BRD\n"
            + "- It tests behaviour the BRD does not mention\n\n"
            + "OUTPUT FORMAT — each object must have EXACTLY these keys:\n"
            + "title (string), priority (P0|P1|P2), tags (string[]), "
            + "gherkin (\"Given ... When ... Then ...\"), "
            + "steps ([{action,expected}]), acceptance (string), "
            + "category (crud|validation|permission|workflow|search|bulk|integration|"
            + "data-quality|security|performance|api), "
            + "spec_ref (EXACT name of the use case or rule from the BRD — mandatory, must be traceable), "
            + "execution_plan ([{description,method,path,body?,capture?,assertions?}]).\n\n"
            + "Priority: P0=core business acceptance criterion from BRD, "
            + "P1=explicitly stated validation rule or edge case, "
            + "P2=boundary condition directly derivable from a stated BRD constraint.\n"
            + "API paths are relative to /crm/v3/{Module}. "
            + "End every create-flow case with a DELETE step using the captured record id. "
            + "Use UAT-Smoke-* sample data with domain-realistic values that match what the BRD describes "
            + "(e.g. if the BRD is about real-estate leads, use realistic property and contact data).\n"
            + "STRICT JSON: all values must be literal — strings, numbers, booleans, arrays, objects. "
            + "NEVER use JavaScript: no .repeat(), no + concatenation, no variable references, "
            + "no spread (...), no method calls, no trailing dots. "
            + "Write actual literal string values (255 real characters, not \"A\".repeat(255)). "
            + "capture paths must be quoted strings like \"data[0].id\". "
            + "Return ONLY a JSON array — no markdown fences, no comments, no prose.";
    }

    private static String buildUserPrompt(String brd, String module, String moduleSchema) {
        return "Target module: " + module + "\n\n"
            + "Standard Zoho CRM field schema for " + module + " "
            + "(use this to identify which BRD fields are standard vs custom):\n"
            + moduleSchema + "\n\n"
            + "=== BRD / UAT SPECIFICATION ===\n"
            + brd + "\n"
            + "=== END OF SPECIFICATION ===\n\n"
            + "Your task:\n"
            + "1. Read the entire specification above.\n"
            + "2. Identify all custom modules, custom fields, use cases, business rules, "
            + "validations, workflows, and permission requirements it describes.\n"
            + "3. For each identified item, generate test cases that verify the EXACT behaviour "
            + "described in the BRD — not generic CRM behaviour.\n"
            + "4. Generate AT LEAST 35 test cases. Aim for complete feature coverage: every named "
            + "use case, every validation rule, every business rule from the BRD must have "
            + "at least one test case.\n"
            + "5. Every spec_ref must cite the exact use-case name or BRD section it tests — "
            + "if you cannot point to where in the BRD a test comes from, do not include it.\n"
            + "6. Each execution_plan must use real Zoho CRM API calls with paths, bodies, and "
            + "assertions that match the actual BRD-described scenario.\n\n"
            + "Return a single JSON array only. No markdown fences, no preamble, no trailing prose.";
    }

    private static String creatorSystemPrompt(String owner, String app) {
        String pathBase = "/" + (owner.isEmpty() ? "{owner}" : owner)
                        + "/" + (app.isEmpty()   ? "{app}"   : app);
        return "You are a senior QA engineer at Zoho specializing in UAT for Zoho Creator applications. "
            + "Your job is to produce test cases that are DEEPLY SPECIFIC to the provided form spec — "
            + "not generic form-submission tests.\n\n"
            + "PHASE 1 — ANALYZE THE BRD (internalize before writing any test case):\n"
            + "a) List every form field by name, type, and whether it is required.\n"
            + "b) Note every field-level validation rule: format, allowed values, min/max length, "
            + "regex patterns, uniqueness constraints.\n"
            + "c) Extract every USE CASE described for this form — give each a short name to reuse as spec_ref.\n"
            + "d) Note conditional visibility rules and cross-field dependencies.\n"
            + "e) Note any post-submit actions: workflow triggers, email notifications, record updates, "
            + "integrations, approval flows.\n\n"
            + "PHASE 2 — GENERATE TEST CASES (one test per distinct BRD scenario):\n"
            + "- For EVERY use case identified, generate the primary success-path submission test.\n"
            + "- For EVERY required field, generate a test that omits it and asserts the rejection.\n"
            + "- For EVERY format/type constraint, generate a test with invalid data and assert rejection.\n"
            + "- For EVERY conditional or cross-field rule, generate a test that triggers it.\n"
            + "- For EVERY post-submit workflow described, generate a test that verifies the outcome.\n"
            + "- Do NOT test behaviour the BRD does not describe.\n\n"
            + "QUALITY GATE — discard any test case where:\n"
            + "- It could be written without reading this specific BRD\n"
            + "- spec_ref cannot be traced to a named use case, field, or rule in the spec\n\n"
            + "OUTPUT FORMAT — each object must have EXACTLY these keys:\n"
            + "title (string), priority (P0|P1|P2), tags (string[]), "
            + "gherkin (\"Given ... When ... Then ...\"), "
            + "steps ([{action,expected}]), acceptance (string), "
            + "category (crud|validation|permission|workflow|search|integration|"
            + "data-quality|security|performance|api), "
            + "spec_ref (EXACT use-case name or field name from the BRD — mandatory), "
            + "execution_plan ([{description,method,path,body?,capture?,assertions?}]).\n\n"
            + "Creator API paths:\n"
            + "- List: GET " + pathBase + "/{form_link_name}\n"
            + "- Submit/create: POST " + pathBase + "/{form_link_name}\n"
            + "- Read one: GET " + pathBase + "/{form_link_name}/{record_id}\n"
            + "- Update: PATCH " + pathBase + "/{form_link_name}/{record_id}\n"
            + "- Delete: DELETE " + pathBase + "/{form_link_name}/{record_id}\n"
            + "- Search: GET " + pathBase + "/{form_link_name}?criteria=(FieldName=\"value\")\n\n"
            + "Creator response: { \"code\": 3000, \"data\": [...] }. "
            + "Assert success: { \"path\": \"code\", \"equals\": \"3000\" }. "
            + "Assert error: { \"path\": \"code\", \"equals\": \"3200\" }. "
            + "Capture ID: { \"record_id\": \"data.ID\" }. "
            + "End create-flow cases with DELETE using captured record_id.\n"
            + "Priority: P0=core submission use case, P1=explicitly stated validation rule, "
            + "P2=boundary condition derivable from a stated constraint.\n"
            + "STRICT JSON: literal values only. No JS expressions. "
            + "Return ONLY a JSON array — no markdown, no comments, no prose.";
    }

    private static String buildCreatorUserPrompt(String brd, String formName,
                                                  String owner, String app) {
        return "Platform: Zoho Creator\n"
            + "Owner: " + owner + "\n"
            + "App: " + app + "\n"
            + "Form: " + formName + "\n\n"
            + "=== BRD / FORM SPECIFICATION ===\n"
            + brd + "\n"
            + "=== END OF SPECIFICATION ===\n\n"
            + "Your task:\n"
            + "1. Read the entire specification above.\n"
            + "2. Identify every field (name, type, required, validation rules), every use case, "
            + "and every business rule or post-submit workflow it describes.\n"
            + "3. For each identified item, generate test cases that verify the EXACT behaviour "
            + "described — not generic form tests.\n"
            + "4. Generate AT LEAST 30 test cases. Every named use case, every required-field "
            + "validation, and every conditional rule must be covered.\n"
            + "5. Every spec_ref must cite the exact use-case name or field from the BRD.\n"
            + "6. Each execution_plan must use real Creator API calls matching the described scenario.\n\n"
            + "Return a single JSON array only. No markdown, no preamble, no trailing prose.";
    }
}
