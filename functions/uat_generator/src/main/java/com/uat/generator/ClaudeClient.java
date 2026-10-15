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

    /** Generates cases for one BRD chunk. The response is a JSON object {"cases":[...]}. */
    public static LlmResult generateChunk(GenerationRequest r) throws Exception {
        boolean creator = "creator".equalsIgnoreCase(r.platform);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model());
        payload.put("max_tokens", maxTokens());

        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                creator ? creatorSystemPrompt(r.creatorOwner, r.creatorApp) : systemPrompt());
        messages.addObject().put("role", "user").put("content", buildChunkPrompt(r, creator));

        return callOpenAICompat(requireToken(), endpoint(), payload);
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
            + "Document outline:\n" + BrdChunker.chunk(brd, 12000, 1).outline + "\n\n"
            + "UAT spec document:\n" + safeBrd(brd) + "\n\n"
            + "Return JSON only: {\"suggested_modules\":[...],\"analysis\":\"...\"}";

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", 512);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", sysPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        return callOpenAICompat(token, endpoint, payload).content;
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

    /**
     * Output-token budget per call. GitHub Models caps tokens per request on most
     * plans (about 4k output), which is why generation is split into small batches.
     */
    private static int maxTokens() {
        try {
            String v = System.getenv("LLM_MAX_TOKENS");
            return v == null || v.isEmpty() ? 4000 : Math.max(500, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 4000;
        }
    }

    // Used by analyze() only: module suggestion needs the gist, not every requirement.
    // Generation no longer truncates; it splits the BRD with BrdChunker instead.
    private static String safeBrd(String brd) {
        int limit = 12000; // ~3000 tokens
        return brd.length() > limit ? brd.substring(0, limit) + "\n...[truncated]" : brd;
    }

    private static String endpoint() {
        String e = System.getenv("COPILOT_ENDPOINT");
        return (e != null && !e.isEmpty()) ? e : DEFAULT_ENDPOINT;
    }

    // Flipped off for the rest of this instance's life if the endpoint rejects JSON mode.
    private static volatile boolean jsonModeSupported = true;

    private static LlmResult callOpenAICompat(String token, String endpoint, ObjectNode payload) throws Exception {
        if (jsonModeSupported) {
            payload.putObject("response_format").put("type", "json_object");
        }
        try {
            return post(token, endpoint, payload, true);
        } catch (HttpError e) {
            if (e.code == 400 && payload.has("response_format")
                    && e.body.toLowerCase().contains("response_format")) {
                jsonModeSupported = false;
                payload.remove("response_format");
                return post(token, endpoint, payload, true);
            }
            throw new RuntimeException("GitHub Models API error " + e.code + ": " + e.body);
        }
    }

    private static LlmResult post(String token, String endpoint, ObjectNode payload,
                                  boolean retryOn429) throws Exception {
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(endpoint);
            post.setHeader("Authorization", "Bearer " + token);
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(payload), ContentType.APPLICATION_JSON));

            Object out = http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                int code = response.getCode();
                if (code == 429) {
                    org.apache.hc.core5.http.Header h = response.getFirstHeader("Retry-After");
                    long wait = 10;
                    try { if (h != null) wait = Long.parseLong(h.getValue().trim()); } catch (NumberFormatException ignored) {}
                    return Long.valueOf(wait);
                }
                if (code >= 400) return new HttpError(code, body);
                JsonNode root = MAPPER.readTree(body);
                JsonNode choice = root.path("choices").path(0);
                JsonNode content = choice.path("message").path("content");
                String finish = choice.path("finish_reason").isMissingNode()
                        ? null : choice.path("finish_reason").asText(null);
                return new LlmResult(content.isMissingNode() ? body : content.asText(), finish);
            });

            if (out instanceof LlmResult) return (LlmResult) out;
            if (out instanceof HttpError) throw (HttpError) out;
            long wait = (Long) out;
            if (retryOn429 && wait <= 20) {
                Thread.sleep(wait * 1000);
                return post(token, endpoint, payload, false);
            }
            throw new RuntimeException("GitHub Models rate limit hit (retry after " + wait + "s). "
                    + "Try again shortly, or lower MAX_LLM_CALLS / GEN_CONCURRENCY.");
        }
    }

    private static final class HttpError extends Exception {
        final int code;
        final String body;
        HttpError(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body == null ? "" : body;
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
            + "req_ids (string[] — the requirement IDs this case verifies, from the list given), "
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
            + "Return ONLY a JSON object of the form {\"cases\": [ ... ]} — no markdown fences, no comments, no prose.";
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
            + "req_ids (string[] — the requirement IDs this case verifies, from the list given), "
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
            + "Return ONLY a JSON object of the form {\"cases\": [ ... ]} — no markdown, no comments, no prose.";
    }

    private static String buildChunkPrompt(GenerationRequest r, boolean creator) {
        StringBuilder sb = new StringBuilder();
        if (creator) {
            sb.append("Platform: Zoho Creator\n")
              .append("Owner: ").append(r.creatorOwner).append("\n")
              .append("App: ").append(r.creatorApp).append("\n")
              .append("Form: ").append(r.module).append("\n\n");
        } else {
            sb.append("Target module: ").append(r.module).append("\n\n")
              .append("Field schema for ").append(r.module)
              .append(" (use it to tell standard from custom fields and to pick real api names; ")
              .append("a BRD field missing from a LIVE schema may not be configured yet — test it anyway ")
              .append("and say so in acceptance):\n")
              .append(r.moduleSchema).append("\n\n");
        }
        if (r.totalParts > 1 && !r.outline.isEmpty()) {
            sb.append("Outline of the whole specification (context only):\n")
              .append(r.outline).append("\n\n")
              .append("This request covers PART ").append(r.part).append(" of ").append(r.totalParts)
              .append(". Write cases only for requirements in this part; the other parts are ")
              .append("handled in separate requests.\n\n");
        }
        sb.append("=== SPECIFICATION")
          .append(r.totalParts > 1 ? " — PART " + r.part + "/" + r.totalParts : "")
          .append(" ===\n")
          .append(r.chunkText).append("\n")
          .append("=== END ===\n\n");
        if (!r.requirements.isEmpty()) {
            sb.append("Requirements in this part:\n").append(r.requirements).append("\n")
              .append("Every case must set req_ids to the IDs above that it verifies (at least one), ")
              .append("and spec_ref to the matching requirement title.\n\n");
        }
        sb
          .append("Focus for this request: ").append(r.focus).append("\n")
          .append("Write up to ").append(r.wantedCases).append(" test cases. Write fewer if this part ")
          .append("has fewer distinct scenarios for this focus — never pad with generic tests.\n")
          .append("Keep each execution_plan compact: only the API steps the scenario needs.\n")
          .append("Every spec_ref must cite the exact use-case name or section it tests; if you ")
          .append("cannot point to where a test comes from, leave it out.\n");
        if (!r.retryNote.isEmpty()) {
            sb.append("\nIMPORTANT: ").append(r.retryNote).append("\n");
        }
        sb.append("\nReturn a JSON object {\"cases\": [...]} and nothing else.");
        return sb.toString();
    }
}
