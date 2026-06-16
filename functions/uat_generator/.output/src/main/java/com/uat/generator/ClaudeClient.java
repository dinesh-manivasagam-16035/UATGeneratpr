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

public final class ClaudeClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // GitHub Models hosts Claude models via OpenAI-compatible API
    private static final String DEFAULT_ENDPOINT = "https://models.inference.ai.azure.com/chat/completions";
    private static final String DEFAULT_MODEL = "Claude-3.7-Sonnet";

    private ClaudeClient() {}

    public static String generate(String brd, String module, String moduleSchema) throws Exception {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException(
                "GITHUB_TOKEN env var is not set. Add your GitHub Personal Access Token in Catalyst function env.");
        }

        String model    = System.getenv().getOrDefault("COPILOT_MODEL", DEFAULT_MODEL);
        String endpoint = System.getenv().getOrDefault("COPILOT_ENDPOINT", DEFAULT_ENDPOINT);

        // OpenAI chat-completions format
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", 16000);

        ArrayNode messages = payload.putArray("messages");

        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt());

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", buildUserPrompt(brd, module, moduleSchema));

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
                // OpenAI response: choices[0].message.content
                JsonNode root = MAPPER.readTree(body);
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                return content.isMissingNode() ? body : content.asText();
            });
        }
    }

    private static String systemPrompt() {
        return "You are a senior QA engineer at Zoho. You receive a UAT specification "
            + "document written by the developer (it contains the feature description, "
            + "the list of fields the feature touches, validation rules, and the explicit "
            + "use cases the developer expects to work). Your job is to:\n"
            + " 1. Parse the document for fields (name, type, required/optional, "
            + "    constraints, picklist values, default values) and for stated use cases.\n"
            + " 2. Generate UAT test cases that exercise EXACTLY those fields and "
            + "    use cases — do NOT invent unrelated fields or scenarios.\n"
            + " 3. Add edge-case coverage AROUND each stated use case (boundary values, "
            + "    invalid inputs, missing required fields, permission combos) using the "
            + "    field rules the document declares.\n"
            + " 4. For each case, produce a machine-executable plan against the Zoho CRM "
            + "    v3 REST API (https://www.zohoapis.com/crm/v3 or zohoapis.in for "
            + "    India DC) — use the field names from the spec verbatim.\n\n"
            + "Output ONLY a JSON array. Each element has: title (short), priority "
            + "(P0|P1|P2), tags (array of strings — include the use case name from the spec "
            + "when applicable), gherkin (Given/When/Then), steps (array of "
            + "{action, expected}), acceptance (string referencing the spec line where "
            + "possible), category (one of 'crud'|'validation'|'permission'|'workflow'|"
            + "'search'|'bulk'|'integration'|'data-quality'|'security'|'performance'|'ui'|"
            + "'api'), spec_ref (string — the use case name or section header from the "
            + "spec that this case traces back to), and execution_plan (array of API steps).\n\n"
            + "execution_plan step shape:\n"
            + "  { \"description\": \"...\",\n"
            + "    \"method\": \"GET|POST|PUT|PATCH|DELETE\",\n"
            + "    \"path\": \"/crm/v3/{Module}\"  (relative; executor prepends base URL),\n"
            + "    \"body\": { ... } (JSON, optional; use field api_names from the spec),\n"
            + "    \"capture\": { \"record_id\": \"data[0].details.id\" }  (optional),\n"
            + "    \"assertions\": [ { \"path\": \"data[0].code\", \"equals\": \"SUCCESS\" } ] }\n"
            + "Reference captured values in later steps as {{var_name}}.\n\n"
            + "CASE COUNT: produce AT LEAST one positive + 2 negative cases for EACH "
            + "use case in the spec, plus AT LEAST one boundary case per declared field. "
            + "Minimum 30 cases per module. Do not stop early.\n\n"
            + "PRIORITY: P0 = explicit acceptance criteria from spec. P1 = stated "
            + "edge case in spec. P2 = inferred edge case we added.\n\n"
            + "EDGE-CASE COVERAGE — touch every category below where the spec applies:\n"
            + "1.  CRUD: create with all required, with all optional, edit each field type, "
            + "    soft delete, hard delete, restore from recycle bin, read by id, list with "
            + "    pagination/sorting.\n"
            + "2.  Validation: required-missing, max-length, min-length, unicode/emoji in "
            + "    text, invalid email/phone/url format, date out-of-range, picklist invalid "
            + "    value, numeric overflow, duplicate detection, lookup-target nonexistent.\n"
            + "3.  Permission: standard user, admin, read-only profile, custom profile with "
            + "    field-level permission denied, sharing-rule restricted record, territory "
            + "    access denied.\n"
            + "4.  Workflow & blueprint: stage transition allowed, transition blocked by "
            + "    criteria, mandatory-field-on-transition missing, approval-process pending, "
            + "    automation-rule trigger, scoring rule.\n"
            + "5.  Search: list-view filter, custom-view criteria, free-text search, COQL "
            + "    query, related-list filter.\n"
            + "6.  Bulk: mass update, mass delete, mass transfer, import CSV, bulk-write job, "
            + "    bulk-read job, mass convert.\n"
            + "7.  Integration: webhook delivery, related module update propagation, "
            + "    Marketplace extension hook, ZIA prediction.\n"
            + "8.  Data quality: duplicate-record merge, data-import dedup, mass-fix.\n"
            + "9.  Security: XSS in description, SQL-injection-like input in search, "
            + "    rate-limit on burst, OAuth scope mismatch, expired token, CSRF behavior.\n"
            + "10. Performance: max-records-per-page boundary, payload-size limit, "
            + "    concurrent-update conflict.\n"
            + "11. UI/UX: form load with conditional fields, autosave, related-list lazy "
            + "    load, mandatory-on-transition prompt.\n"
            + "12. API: malformed JSON, wrong content-type, unsupported HTTP method.\n\n"
            + "RULES:\n"
            + " - End every create-flow case with a DELETE step using the captured id, so "
            + "   the suite is idempotent.\n"
            + " - Use realistic sample data (UAT-Smoke-* prefixes for created records).\n"
            + " - Mix priorities: ~20% P0, ~50% P1, ~30% P2.\n"
            + " - No prose, no markdown fencing — JSON array only.";
    }

    /**
     * Analyze a UAT spec document and suggest up to 3 CRM modules using the
     * GitHub Models API. Returns a raw JSON string:
     * { "suggested_modules": ["Leads",...], "analysis": "..." }
     */
    public static String analyze(String brd, String moduleNames) throws Exception {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("GITHUB_TOKEN not set");
        }

        String model    = System.getenv().getOrDefault("COPILOT_MODEL", DEFAULT_MODEL);
        String endpoint = System.getenv().getOrDefault("COPILOT_ENDPOINT", DEFAULT_ENDPOINT);

        String sysPrompt =
            "You are a Zoho CRM expert. You will receive a UAT specification document and a "
            + "JSON array of CRM module api_names available in the org. "
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
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", sysPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(endpoint);
            post.setHeader("Authorization", "Bearer " + token);
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(payload), ContentType.APPLICATION_JSON));

            return http.execute(post, response -> {
                String body2 = EntityUtils.toString(response.getEntity());
                int code = response.getCode();
                if (code >= 400) {
                    throw new RuntimeException("GitHub Models API error " + code + ": " + body2);
                }
                JsonNode root = MAPPER.readTree(body2);
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                return content.isMissingNode() ? body2 : content.asText();
            });
        }
    }

    private static String buildUserPrompt(String brd, String module, String moduleSchema) {
        return "Module: " + module + "\n\n"
            + "Module schema (CRM defaults — supplement, but DO NOT override the spec):\n"
            + moduleSchema + "\n\n"
            + "UAT specification document (authored by the developer — this is your "
            + "source of truth for fields, validation rules, and use cases):\n"
            + brd + "\n\n"
            + "Generate AT LEAST 35 UAT test cases as a single JSON array. Trace every "
            + "case back to a use case or field from the spec via the spec_ref field. "
            + "No prose, no markdown fencing.";
    }
}
