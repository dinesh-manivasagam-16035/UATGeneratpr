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
        payload.put("max_tokens", 4096);
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
        return "You are a senior QA engineer at Zoho. You generate UAT test cases from a BRD "
            + "for a specific CRM/Desk module, AND you generate a machine-executable plan for "
            + "each case against the Zoho CRM v3 REST API (https://www.zohoapis.com/crm/v3).\n\n"
            + "Output ONLY a JSON array. Each element has: title (short), priority (P0|P1|P2), "
            + "tags (array of strings), gherkin (string with Given/When/Then lines), "
            + "steps (array of {action, expected}), acceptance (string), and execution_plan "
            + "(array of API steps).\n\n"
            + "execution_plan step shape:\n"
            + "  { \"description\": \"...\",\n"
            + "    \"method\": \"GET|POST|PUT|PATCH|DELETE\",\n"
            + "    \"path\": \"/crm/v3/Leads\"   (relative; the executor prepends base URL),\n"
            + "    \"body\": { ... } (JSON, optional),\n"
            + "    \"capture\": { \"record_id\": \"data[0].details.id\" }  (optional; "
            + "JSON-path-ish, stores response value for later steps),\n"
            + "    \"assertions\": [ { \"path\": \"data[0].code\", \"equals\": \"SUCCESS\" }, "
            + "                       { \"path\": \"status_code\", \"equals\": 201 } ] }\n"
            + "Inside body/path of later steps, reference captured values with {{var_name}}.\n\n"
            + "Cover the golden path, boundary cases, permission/role cases, validation errors, "
            + "and one negative path. Aim for 6-10 cases. End every create-flow case with a "
            + "DELETE step that uses the captured id, so runs are idempotent. "
            + "No prose, no markdown fencing.";
    }

    private static String buildUserPrompt(String brd, String module, String moduleSchema) {
        return "Module: " + module + "\n\n"
            + "Module schema (fields, statuses, relationships):\n" + moduleSchema + "\n\n"
            + "BRD:\n" + brd + "\n\n"
            + "Generate UAT test cases as a JSON array now.";
    }
}
