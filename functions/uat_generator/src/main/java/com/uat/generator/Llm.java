package com.uat.generator;

/**
 * Picks the LLM provider for every generation call (UAT cases, implementation blueprint,
 * module suggestions), so callers don't each read LLM_PROVIDER.
 *
 *   LLM_PROVIDER=zia     Zia Agent (ZiaAgentClient)
 *   LLM_PROVIDER=github  GitHub Models (ClaudeClient)
 *   LLM_PROVIDER=mock    offline mocks (handled by the callers)
 *   unset                Zia Agent when it's configured, else GitHub Models
 */
public final class Llm {

    private Llm() {}

    public static String provider() {
        String p = System.getenv("LLM_PROVIDER");
        p = p == null ? "" : p.trim().toLowerCase();
        if ("mock".equals(p)) return "mock";
        if ("zia".equals(p) || "zia_agent".equals(p)) return "zia";
        if ("github".equals(p) || "copilot".equals(p) || "claude".equals(p)) return "github";
        return ZiaAgentClient.configured() ? "zia" : "github";
    }

    public static boolean mock() { return "mock".equals(provider()); }

    /** Shown to users and stored in run logs. */
    public static String label() {
        switch (provider()) {
            case "mock": return "mock";
            case "zia":  return "Zia Agent";
            default:     return "GitHub Models (" + System.getenv().getOrDefault("COPILOT_MODEL", "gpt-4o") + ")";
        }
    }

    /** True when the selected provider has its credentials (mock always does). */
    public static boolean available() {
        switch (provider()) {
            case "mock": return true;
            case "zia":  return ZiaAgentClient.configured();
            default:     return ClaudeClient.configured();
        }
    }

    public static LlmResult chat(String system, String user, int maxTokens) throws Exception {
        if ("zia".equals(provider())) return ZiaAgentClient.chat(system, user);
        return ClaudeClient.chat(system, user, maxTokens);
    }

    public static LlmResult generateChunk(GenerationRequest r) throws Exception {
        if ("zia".equals(provider())) {
            String[] p = ClaudeClient.chunkPrompts(r);
            return ZiaAgentClient.chat(p[0], p[1]);
        }
        return ClaudeClient.generateChunk(r);
    }

    public static String analyze(String brd, String moduleNames) throws Exception {
        if ("zia".equals(provider())) {
            String[] p = ClaudeClient.analyzePrompts(brd, moduleNames);
            return ZiaAgentClient.chat(p[0], p[1]).content;
        }
        return ClaudeClient.analyze(brd, moduleNames);
    }
}
