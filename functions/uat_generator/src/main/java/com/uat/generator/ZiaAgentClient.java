package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * LLM provider backed by a deployed Zia Agent (Zoho Agent Studio), through the Agent API:
 *
 *   POST https://ziaagents.zoho.com/ziaagents/api/v1/agents/query
 *   X-ZIAAGENTS-ORG, X-ZIAAGENTS-AGENT-ID, X-ZIAAGENTS-AGENT-VERSION-ID,
 *   Authorization: Zoho-oauthtoken {access token, scope ZiaAgents.agents.TRIGGER}
 *   {"query": "...", "systemArgs": {}}   →   {"data": {"response": "..."}}
 *
 * (zoho.com/agents/resources/help/developer-documentation/api-reference.html)
 *
 * The agent is a plain generator with no tools (see agent/ZIA_GENERATOR.md): every request
 * carries its own instructions and asks for JSON, exactly as the GitHub Models path does,
 * and the reply is parsed by the same code (CaseParser / ImplSpec, with salvage).
 *
 * Env:
 *   ZIA_AGENT_ORG_ID, ZIA_AGENT_ID, ZIA_AGENT_VERSION_ID   from the agent's Integrate tab
 *   ZIA_AGENT_REFRESH_TOKEN                                self-client refresh token (scope above)
 *   ZIA_AGENT_CLIENT_ID, ZIA_AGENT_CLIENT_SECRET           that self client (default: ZOHO_CLIENT_ID/SECRET)
 *   ZIA_AGENT_URL        default: the Agent API on the token's data center, e.g.
 *                        https://ziaagents.zoho.in/ziaagents/api/v1/agents/query for accounts.zoho.in
 *   ZIA_ACCOUNTS_BASE    default ZOHO_ACCOUNTS_BASE, else from ZOHO_DC (accounts.zoho.in for in)
 *   ZIA_AGENT_TIMEOUT_SEC  per call, default 25 (a Catalyst request is stopped at ~30 s)
 */
public final class ZiaAgentClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_URL = "https://ziaagents.zoho.com/ziaagents/api/v1/agents/query";

    /** Per-call timeout set by long-running callers (the impl_worker job); 0 = use the env/default. */
    static volatile int timeoutSecOverride = 0;

    private ZiaAgentClient() {}

    /** True when the agent ids and a refresh token are set. */
    public static boolean configured() {
        return missing().isEmpty();
    }

    /** Names (never values) of the variables a call needs but the function doesn't have. */
    public static java.util.List<String> missing() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : new String[]{"ZIA_AGENT_ORG_ID", "ZIA_AGENT_ID", "ZIA_AGENT_VERSION_ID", "ZIA_AGENT_REFRESH_TOKEN"}) {
            if (!notEmpty(env(k))) out.add(k);
        }
        if (!notEmpty(env("ZIA_AGENT_CLIENT_ID")) && !notEmpty(env("ZOHO_CLIENT_ID"))) out.add("ZIA_AGENT_CLIENT_ID");
        if (!notEmpty(env("ZIA_AGENT_CLIENT_SECRET")) && !notEmpty(env("ZOHO_CLIENT_SECRET"))) out.add("ZIA_AGENT_CLIENT_SECRET");
        return out;
    }

    /** Names of the Zia variables that are set (for setup checks; values are never shown). */
    public static java.util.List<String> present() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : new String[]{"ZIA_AGENT_ORG_ID", "ZIA_AGENT_ID", "ZIA_AGENT_VERSION_ID", "ZIA_AGENT_REFRESH_TOKEN",
                "ZIA_AGENT_CLIENT_ID", "ZIA_AGENT_CLIENT_SECRET", "ZIA_ACCOUNTS_BASE", "ZIA_AGENT_URL", "LLM_PROVIDER"}) {
            if (notEmpty(env(k))) out.add(k);
        }
        return out;
    }

    /** One generation call: the instructions and the request go in one query. */
    public static LlmResult chat(String system, String user) throws Exception {
        if (!configured()) {
            throw new IllegalStateException("Zia Agent isn't configured: set ZIA_AGENT_ORG_ID, ZIA_AGENT_ID, "
                    + "ZIA_AGENT_VERSION_ID and ZIA_AGENT_REFRESH_TOKEN on the function (see agent/ZIA_GENERATOR.md).");
        }
        String query = "INSTRUCTIONS\n" + system + "\n\nREQUEST\n" + user
                + "\n\nReply with the JSON object only: no markdown fences, no text before or after it.";
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        body.putObject("systemArgs");
        return post(body, true);
    }

    private static LlmResult post(ObjectNode body, boolean retryOn429) throws Exception {
        String token = tokenClient().accessToken();
        // 25 s inside an Advanced I/O request (~30 s limit); the impl_worker job sets a longer one.
        int timeout = timeoutSecOverride > 0 ? timeoutSecOverride : envInt("ZIA_AGENT_TIMEOUT_SEC", 25);
        RequestConfig cfg = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(timeout))
                .setConnectionRequestTimeout(Timeout.ofSeconds(20))
                .build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            HttpPost post = new HttpPost(agentUrl());
            post.setHeader("X-ZIAAGENTS-ORG", env("ZIA_AGENT_ORG_ID"));
            post.setHeader("X-ZIAAGENTS-AGENT-ID", env("ZIA_AGENT_ID"));
            post.setHeader("X-ZIAAGENTS-AGENT-VERSION-ID", env("ZIA_AGENT_VERSION_ID"));
            post.setHeader("Authorization", "Zoho-oauthtoken " + token);
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(body), ContentType.APPLICATION_JSON));

            Object out = http.execute(post, response -> {
                String text = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
                int code = response.getCode();
                if (code == 429) return Integer.valueOf(429);
                if (code >= 400) {
                    String hint = code == 401 ? " Called " + agentUrl() + " with a token from " + accountsBase()
                            + ": both must be on the same data center, and the token needs scope ZiaAgents.agents.TRIGGER."
                            : code == 404 || text.contains("VERSION") ? " If the agent was redeployed, update ZIA_AGENT_VERSION_ID." : "";
                    return new RuntimeException("Zia Agent error " + code + ": " + trim(text, 300) + hint);
                }
                JsonNode root;
                try { root = MAPPER.readTree(text); } catch (Exception e) { return new LlmResult(text, null); }
                JsonNode resp = root.path("data").path("response");
                if (resp.isMissingNode() || resp.isNull()) {
                    return new RuntimeException("Zia Agent returned no data.response: " + trim(text, 300));
                }
                // The response is usually a string; an agent may also return structured JSON.
                return new LlmResult(resp.isTextual() ? resp.asText() : resp.toString(), null);
            });
            if (out instanceof LlmResult) return (LlmResult) out;
            if (out instanceof RuntimeException) throw (RuntimeException) out;
            if (retryOn429) {
                Thread.sleep(8000);
                return post(body, false);
            }
            throw new RuntimeException("Zia Agent rate limit hit. Try again shortly, or lower GEN_CONCURRENCY.");
        }
    }

    /** Refreshes the access token through CrmClient's shared per-instance cache (about once an hour). */
    private static CrmClient tokenClient() {
        String clientId = orDefault(env("ZIA_AGENT_CLIENT_ID"), env("ZOHO_CLIENT_ID"));
        String secret = orDefault(env("ZIA_AGENT_CLIENT_SECRET"), env("ZOHO_CLIENT_SECRET"));
        if (!notEmpty(clientId) || !notEmpty(secret)) {
            throw new IllegalStateException("Set ZIA_AGENT_CLIENT_ID and ZIA_AGENT_CLIENT_SECRET (the self client that issued ZIA_AGENT_REFRESH_TOKEN).");
        }
        return new CrmClient("", accountsBase(), clientId, secret, env("ZIA_AGENT_REFRESH_TOKEN"));
    }

    /**
     * The Agent API on the same data center as the token: a token from accounts.zoho.in only
     * works on ziaagents.zoho.in (INVALID_OAUTHTOKEN otherwise). ZIA_AGENT_URL overrides.
     */
    static String agentUrl() {
        String u = env("ZIA_AGENT_URL");
        if (notEmpty(u)) return u.trim();
        String acc = accountsBase();
        int at = acc.indexOf("accounts.zoho.");
        if (at < 0) return DEFAULT_URL;
        String tld = acc.substring(at + "accounts.zoho.".length()).replaceAll("/.*$", "");
        return "https://ziaagents.zoho." + tld + "/ziaagents/api/v1/agents/query";
    }

    static String accountsBase() {
        String b = orDefault(env("ZIA_ACCOUNTS_BASE"), env("ZOHO_ACCOUNTS_BASE"));
        if (notEmpty(b)) return b.trim();
        String dc = env("ZOHO_DC");
        if (!notEmpty(dc) || "com".equalsIgnoreCase(dc.trim())) return "https://accounts.zoho.com";
        return "https://accounts.zoho." + dc.trim().toLowerCase();
    }

    private static String env(String k) { return System.getenv(k); }
    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }
    private static String orDefault(String s, String d) { return notEmpty(s) ? s.trim() : d; }
    private static String trim(String s, int max) { return s == null ? "" : s.length() > max ? s.substring(0, max) + "…" : s; }
    private static int envInt(String k, int d) {
        try { return Integer.parseInt(env(k).trim()); } catch (Exception e) { return d; }
    }
}
