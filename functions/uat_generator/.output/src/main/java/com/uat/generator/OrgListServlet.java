package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * GET /crm/orgs
 *
 * Returns the list of Zoho organisations the signed-in user belongs to, plus
 * which one is currently connected.  Used by the frontend org-picker.
 *
 * Response when no CRM token exists:
 *   { "needs_auth": true, "oauth_available": true|false }
 *
 * Response when a CRM token is present:
 *   {
 *     "needs_auth": false,
 *     "current": { "org_id": "…", "org_name": "…", "api_domain": "…" },
 *     "orgs": [ { "org_id": "…", "org_name": "…", "org_domain": "…", "dc": "…" }, … ]
 *   }
 *
 * The "orgs" list comes from GET <accountsBase>/accounts/v2/orgs (requires
 * AaaServer.profile.READ scope, added in CrmAuthServlet).  If that call fails
 * the array contains only the currently-connected org so the UI still works.
 */
public class OrgListServlet extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.apply(req, resp);
        resp.setContentType("application/json");

        ObjectNode out = MAPPER.createObjectNode();
        boolean oauthAvailable = notEmpty(System.getenv("ZOHO_CLIENT_ID"));

        String sid = cookieValue(req, "tp_crm_sid");
        CrmTokenStore.TokenBundle bundle = CrmTokenStore.get(sid);

        if (bundle == null || bundle.isExpired()) {
            out.put("needs_auth", true);
            out.put("oauth_available", oauthAvailable);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
            return;
        }

        // Build the "current" node from the stored bundle.
        ObjectNode current = MAPPER.createObjectNode();
        current.put("org_id",     bundle.orgId   != null ? bundle.orgId   : "");
        current.put("org_name",   bundle.orgName  != null ? bundle.orgName  : "");
        current.put("api_domain", bundle.apiBase);
        out.put("needs_auth", false);
        out.set("current", current);

        // Attempt to fetch the full org list from Zoho Accounts.
        ArrayNode orgs = fetchOrgList(bundle);
        out.set("orgs", orgs);

        resp.getWriter().write(MAPPER.writeValueAsString(out));
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.applyOptions(req, resp);
    }

    /**
     * Calls GET <accountsBase>/accounts/v2/orgs.
     * Falls back to a single-item array with the current org if the call fails
     * or the token lacks AaaServer.profile.READ scope.
     */
    private ArrayNode fetchOrgList(CrmTokenStore.TokenBundle bundle) {
        ArrayNode arr = MAPPER.createArrayNode();
        try {
            // Refresh the access token if needed so we can make the accounts call.
            String accessToken = bundle.accessToken;
            if (bundle.isExpired() && bundle.hasOAuthCredentials()) {
                // Attempt a silent refresh; fall back to current token on error.
                try {
                    CrmClient tmp = bundle.toCrmClient();
                    // getAccessToken() is package-private; call a no-op GET that
                    // triggers the internal refresh logic instead.
                    tmp.call("GET", "/crm/v3/org", null);
                    accessToken = bundle.accessToken; // refreshed in-place by CrmTokenStore.refresh()
                } catch (Exception ignored) {
                    // Use cached token even if potentially expired.
                }
            }

            try (CloseableHttpClient http = HttpClients.createDefault()) {
                HttpGet get = new HttpGet(bundle.accountsBase + "/accounts/v2/orgs");
                get.setHeader("Authorization", "Zoho-oauthtoken " + accessToken);
                String body = http.execute(get, r -> EntityUtils.toString(r.getEntity()));
                JsonNode root = MAPPER.readTree(body);

                // Zoho returns { "status": "success", "data": { "orgs": [...] } }
                // or sometimes just { "orgs": [...] } — handle both.
                JsonNode orgList = root.path("data").path("orgs");
                if (!orgList.isArray()) orgList = root.path("orgs");

                if (orgList.isArray() && orgList.size() > 0) {
                    for (JsonNode o : orgList) {
                        ObjectNode item = MAPPER.createObjectNode();
                        item.put("org_id",     o.path("id").asText(o.path("org_id").asText("")));
                        item.put("org_name",   o.path("company_name").asText(o.path("org_name").asText("")));
                        item.put("org_domain", o.path("alias").asText(o.path("org_domain").asText("")));
                        item.put("dc",         o.path("dc").asText(""));
                        arr.add(item);
                    }
                    return arr;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the single-org fallback below.
        }

        // Fallback: return just the currently-connected org.
        if (notEmpty(bundle.orgName) || notEmpty(bundle.orgId)) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("org_id",     bundle.orgId   != null ? bundle.orgId   : "");
            item.put("org_name",   bundle.orgName  != null ? bundle.orgName  : "Connected org");
            item.put("org_domain", "");
            item.put("dc",         "");
            arr.add(item);
        }
        return arr;
    }

    private static String cookieValue(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
