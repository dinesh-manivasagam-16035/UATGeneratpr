package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates calls from a Zia Agent.
 *
 * The agent's tool group uses a Zoho OAuth connection (Zoho CRM scopes), so each
 * call arrives with "Authorization: Zoho-oauthtoken <token>". LiftOff checks the
 * token by reading the CRM org it belongs to, and accepts it only if that org is
 * in LIFTOFF_AGENT_ORG_IDS (comma-separated). With that env var unset, agent
 * access is off: this fails closed.
 *
 * The same token is then used for the run's CRM calls (field metadata, live
 * execution), so the agent acts with the connected user's own CRM permissions.
 * Validated tokens are cached for 10 minutes per instance.
 */
public final class AgentAuth {

    /** Request attribute holding the CrmClient for an authenticated agent call. */
    public static final String ATTR = "liftoff.agent.crm";

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final ConcurrentHashMap<String, Long> VALID = new ConcurrentHashMap<>();

    private AgentAuth() {}

    public static final class Denied extends Exception {
        public final int status;
        Denied(int status, String message) { super(message); this.status = status; }
    }

    /** The CrmClient for this agent call, or throws Denied (401/403/503). */
    public static CrmClient authenticate(HttpServletRequest req) throws Denied {
        Set<String> allowed = allowedOrgs();
        if (allowed.isEmpty()) {
            throw new Denied(503, "Agent access is not enabled. Set LIFTOFF_AGENT_ORG_IDS on the server.");
        }
        String token = bearer(req.getHeader("Authorization"));
        if (token == null) {
            throw new Denied(401, "Missing Authorization header. Attach a Zoho CRM connection to the tool group.");
        }
        CrmClient client = CrmClient.withAccessToken(apiBase(), token);

        String key = hash(token);
        Long until = VALID.get(key);
        if (until != null && until > System.currentTimeMillis()) return client;

        String orgId;
        String zgid;
        try {
            CrmClient.Response r = client.call("GET", "/crm/v3/org", null);
            if (r.statusCode == 401) throw new Denied(401, "The Zoho token was rejected by CRM.");
            if (r.statusCode >= 400 || r.json == null) {
                throw new Denied(401, "Couldn't verify the Zoho token (CRM returned HTTP " + r.statusCode + ").");
            }
            JsonNode org = r.json.path("org").path(0);
            orgId = org.path("id").asText("");
            zgid = org.path("zgid").asText("");
        } catch (Denied d) {
            throw d;
        } catch (Exception e) {
            throw new Denied(401, "Couldn't verify the Zoho token: " + e.getMessage());
        }
        // Accept either the CRM org id or the org's zgid (shown in CRM Setup > Company Details).
        if (!allowed.contains(orgId) && !allowed.contains(zgid)) {
            throw new Denied(403, "This CRM org (" + orgId + ") is not allowed to use LiftOff's agent API. "
                    + "Add it to LIFTOFF_AGENT_ORG_IDS.");
        }
        VALID.put(key, System.currentTimeMillis() + CACHE_MS);
        return client;
    }

    static String apiBase() {
        String env = System.getenv("ZOHO_API_BASE");
        return env != null && !env.trim().isEmpty() ? env.trim() : CrmAuthServlet.apiBase();
    }

    private static Set<String> allowedOrgs() {
        Set<String> out = new HashSet<>();
        String v = System.getenv("LIFTOFF_AGENT_ORG_IDS");
        if (v == null) return out;
        for (String s : v.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private static String bearer(String header) {
        if (header == null) return null;
        String h = header.trim();
        for (String prefix : new String[]{"Zoho-oauthtoken ", "Bearer "}) {
            if (h.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String t = h.substring(prefix.length()).trim();
                return t.isEmpty() ? null : t;
            }
        }
        return null;
    }

    private static String hash(String token) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
