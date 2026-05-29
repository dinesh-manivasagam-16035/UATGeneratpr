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
    private static final String DEFAULT_MODEL = "claude-opus-4-7";
    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private ClaudeClient() {}

    public static String generate(String brd, String module, String moduleSchema) throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                "ANTHROPIC_API_KEY env var is not set. Configure it in Catalyst function env.");
        }

        String model = System.getenv().getOrDefault("CLAUDE_MODEL", DEFAULT_MODEL);
        String endpoint = System.getenv().getOrDefault("ANTHROPIC_ENDPOINT", DEFAULT_ENDPOINT);

        String userPrompt = buildUserPrompt(brd, module, moduleSchema);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        // Each case is ~400-600 tokens incl. execution_plan; need plenty for 30+ cases.
        payload.put("max_tokens", 16000);
        payload.put("system", systemPrompt());
        ArrayNode messages = payload.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", userPrompt);

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(endpoint);
            post.setHeader("x-api-key", apiKey);
            post.setHeader("anthropic-version", API_VERSION);
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(payload), ContentType.APPLICATION_JSON));

            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                int code = response.getCode();
                if (code >= 400) {
                    throw new RuntimeException("Anthropic API error " + code + ": " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                JsonNode contentArr = root.path("content");
                if (contentArr.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode c : contentArr) {
                        if ("text".equals(c.path("type").asText())) {
                            sb.append(c.path("text").asText());
                        }
                    }
                    return sb.toString();
                }
                return body;
            });
        }
    }

    private static String systemPrompt() {
        return "You are a senior QA engineer at Zoho. You generate exhaustive UAT test cases "
            + "from a BRD for a specific CRM module, AND you generate a machine-executable plan "
            + "for each case against the Zoho CRM v3 REST API (https://www.zohoapis.com/crm/v3 "
            + "or zohoapis.in for India DC).\n\n"
            + "Output ONLY a JSON array. Each element has: title (short), priority (P0|P1|P2), "
            + "tags (array of strings), gherkin (string with Given/When/Then lines), "
            + "steps (array of {action, expected}), acceptance (string), category (one of "
            + "'crud'|'validation'|'permission'|'workflow'|'search'|'bulk'|'integration'|"
            + "'data-quality'|'security'|'performance'|'ui'|'api'), and execution_plan "
            + "(array of API steps).\n\n"
            + "execution_plan step shape:\n"
            + "  { \"description\": \"...\",\n"
            + "    \"method\": \"GET|POST|PUT|PATCH|DELETE\",\n"
            + "    \"path\": \"/crm/v3/{Module}\"  (relative; executor prepends base URL),\n"
            + "    \"body\": { ... } (JSON, optional),\n"
            + "    \"capture\": { \"record_id\": \"data[0].details.id\" }  (optional),\n"
            + "    \"assertions\": [ { \"path\": \"data[0].code\", \"equals\": \"SUCCESS\" } ] }\n"
            + "Reference captured values in later steps as {{var_name}}.\n\n"
            + "CASE COUNT: produce AT LEAST 30 cases per module. Do not stop early.\n\n"
            + "EDGE-CASE COVERAGE — touch every category below, multiple times each:\n"
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
            + "Module schema (fields, statuses, relationships):\n" + moduleSchema + "\n\n"
            + "BRD:\n" + brd + "\n\n"
            + "Generate AT LEAST 35 UAT test cases as a single JSON array covering every "
            + "category in the system prompt. No prose, no markdown fencing.";
    }
}
