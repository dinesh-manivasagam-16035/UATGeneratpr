package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * POST /crm/select-org {"org_id": "...", "org_name"?: "..."}
 *
 * Switches the connected session to another CRM org of the same Zoho user. A user in several
 * orgs is otherwise always routed to their default org; CrmClient sends the chosen id as the
 * X-CRM-ORG header on every call. The org is checked first (GET /org with that header), the
 * session cookie is re-issued with it, and {ok, org_id, org_name} comes back.
 */
public class CrmSelectOrgServlet extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.apply(req, resp);
        resp.setContentType("application/json");
        String sid = null;
        Cookie[] cookies = req.getCookies();
        if (cookies != null) for (Cookie c : cookies) if ("tp_crm_sid".equals(c.getName())) sid = c.getValue();
        CrmTokenStore.TokenBundle bundle = CrmTokenStore.get(sid);
        if (bundle == null || bundle.isExpired() || !bundle.hasOAuthCredentials()) {
            ImplementServlet.writeError(resp, 401, "Connect CRM first.");
            return;
        }
        JsonNode body;
        try { body = MAPPER.readTree(req.getInputStream()); } catch (Exception e) { body = MAPPER.createObjectNode(); }
        String orgId = body.path("org_id").asText("").trim();
        if (orgId.isEmpty()) { ImplementServlet.writeError(resp, 400, "Field 'org_id' is required."); return; }

        // Make sure the token really reaches that org before switching the session to it.
        CrmClient probe = bundle.toCrmClient().forOrg(orgId);
        String orgName = body.path("org_name").asText("");
        try {
            CrmClient.Response r = probe.call("GET", "/crm/v3/org", null);
            if (r.statusCode >= 400 || r.json == null) {
                ImplementServlet.writeError(resp, 502, "CRM didn't accept org " + orgId + ": HTTP " + r.statusCode
                        + (r.rawBody == null ? "" : ": " + ImplementServlet.trim(r.rawBody, 200)));
                return;
            }
            JsonNode org = r.json.path("org").path(0);
            String liveId = org.path("id").asText("");
            if (!liveId.isEmpty() && !liveId.equals(orgId) && !liveId.equals(org.path("zgid").asText(""))) {
                // The header was ignored (single-org user, or id of another kind): say so plainly.
                ImplementServlet.writeError(resp, 409, "CRM routed the call to org " + liveId + " ("
                        + org.path("company_name").asText("") + ") instead of " + orgId
                        + ". Use the CRM org id (Setup → Company Details → Org ID) of an org this user belongs to.");
                return;
            }
            if (orgName.isEmpty()) orgName = org.path("company_name").asText("");
        } catch (Exception e) {
            ImplementServlet.writeError(resp, 502, "Couldn't reach org " + orgId + ": " + ImplementServlet.trim(e.getMessage(), 200));
            return;
        }

        String newSid = CrmTokenStore.withOrg(bundle, orgId, orgName);
        CrmTokenStore.setCookie(req, resp, newSid, CrmTokenStore.SESSION_DAYS * 24 * 60 * 60);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("ok", true);
        out.put("org_id", orgId);
        out.put("org_name", orgName);
        resp.getWriter().write(MAPPER.writeValueAsString(out));
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.applyOptions(req, resp);
    }
}
