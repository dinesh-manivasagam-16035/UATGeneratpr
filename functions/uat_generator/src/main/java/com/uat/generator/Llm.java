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

    /** User-facing message for when no provider can run, naming any missing Zia variables. */
    public static String notConfiguredMessage() {
        java.util.List<String> miss = ZiaAgentClient.missing();
        java.util.List<String> have = ZiaAgentClient.present();
        String setup = " Set them in Catalyst → Serverless → Functions → this function → Configuration → Environment "
                + "Variables; uat_generator and impl_worker each need their own copy (see agent/ZIA_GENERATOR.md).";
        if ("zia".equals(provider()) || !have.isEmpty()) {
            return "The Zia Agent isn't fully configured. Missing on this function: " + String.join(", ", miss) + "."
                    + (have.isEmpty() ? "" : " Found: " + String.join(", ", have) + ".") + setup;
        }
        return "No LLM is configured: none of the Zia Agent variables are set on this function ("
                + String.join(", ", miss) + ")." + setup + " Or set GITHUB_TOKEN to use GitHub Models.";
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
