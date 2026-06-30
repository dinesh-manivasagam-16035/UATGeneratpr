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
 * LLM client using the GitHub Copilot API (OpenAI-compatible).
 *
 * Required env var (set in Catalyst function environment):
 *   GITHUB_TOKEN  — GitHub PAT or OAuth token with Copilot access
 *                   (same token you use for Copilot in VS Code)
 *
 * Optional:
 *   COPILOT_MODEL    — model ID (default: claude-3.7-sonnet)
 *   COPILOT_ENDPOINT — override the completions endpoint
 */
public final class ClaudeClient {

    private static final ObjectMapper MAPPER           = new ObjectMapper();
    private static final String       DEFAULT_ENDPOINT = "https://api.githubcopilot.com/chat/completions";
    private static final String       DEFAULT_MODEL    = "claude-3.7-sonnet";

    private ClaudeClient() {}

    public static String generate(String brd, String module, String moduleSchema) throws Exception {
        String token    = requireToken();
        String model    = model();
        String endpoint = endpoint();

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", 16000);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        messages.addObject().put("role", "user").put("content", buildUserPrompt(brd, module, moduleSchema));

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
                "GITHUB_TOKEN env var is not set. Generate a token at github.com/settings/tokens "
                + "and add it to Catalyst function env vars.");
        }
        return t;
    }

    private static String model() {
        String m = System.getenv("COPILOT_MODEL");
        return (m != null && !m.isEmpty()) ? m : DEFAULT_MODEL;
    }

    private static String endpoint() {
        String e = System.getenv("COPILOT_ENDPOINT");
        return (e != null && !e.isEmpty()) ? e : DEFAULT_ENDPOINT;
    }

    private static String callOpenAICompat(String githubToken, String endpoint, ObjectNode payload) throws Exception {
        String copilotToken = CopilotTokenManager.getToken(githubToken);
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(endpoint);
            post.setHeader("Authorization", "Bearer " + copilotToken);
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Copilot-Integration-Id", "vscode-chat");
            post.setHeader("editor-version", "vscode/1.85.0");
            post.setHeader("editor-plugin-version", "copilot-chat/0.22.4");
            post.setHeader("user-agent", "GitHubCopilotChat/0.22.4");
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(payload), ContentType.APPLICATION_JSON));

            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                int code = response.getCode();
                if (code >= 400) {
                    throw new RuntimeException("GitHub Copilot API error " + code + ": " + body);
                }
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
