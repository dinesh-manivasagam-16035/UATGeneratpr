package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /creator/forms
 *
 * Lists Zoho Creator apps and their forms via the stored OAuth session.
 * Requires ZohoCreator.meta.READ scope (added to CrmAuthServlet.SCOPES).
 *
 * Response: { "owner": "...", "source": "live", "forms": [
 *   { "app": "appLinkName", "app_name": "App Name",
 *     "api_name": "formLinkName", "display_name": "Form Name" }
 * ]}
 */
public class CreatorModulesServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(CreatorModulesServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        try {
            String sid = cookieValue(req, "tp_crm_sid");
            CrmTokenStore.TokenBundle bundle = CrmTokenStore.get(sid);

            if (bundle == null || !bundle.hasOAuthCredentials()) {
                writeError(resp, HttpServletResponse.SC_UNAUTHORIZED,
                        "Not connected. Sign in via Zoho first (Creator scopes required).");
                return;
            }

            String creatorBase  = creatorBase();
            String accountsBase = bundle.accountsBase;
            String owner        = ownerFromEmail(bundle.email);

            CrmClient creatorClient = new CrmClient(
                    creatorBase, accountsBase,
                    bundle.clientId, bundle.clientSecret, bundle.refreshToken);

            ObjectNode out = MAPPER.createObjectNode();
            out.put("owner", owner);
            ArrayNode formsArr = out.putArray("forms");

            CrmClient.Response appsResp = creatorClient.call("GET", "/" + owner + "/apps", null);
            if (appsResp.statusCode == 200 && appsResp.json != null) {
                JsonNode apps = appsResp.json.path("data");
                if (!apps.isArray()) apps = appsResp.json; // fallback: root might be the array
                for (JsonNode app : apps) {
                    String appLink = app.path("link_name").asText(app.path("app_link_name").asText(""));
                    String appName = app.path("application_name").asText(
                            app.path("app_name").asText(appLink));
                    if (appLink.isEmpty()) continue;
                    try {
                        CrmClient.Response formsResp = creatorClient.call(
                                "GET", "/" + owner + "/" + appLink + "/form", null);
                        if (formsResp.statusCode == 200 && formsResp.json != null) {
                            JsonNode fdata = formsResp.json.path("data");
                            if (!fdata.isArray()) fdata = formsResp.json;
                            for (JsonNode form : fdata) {
                                String formLink = form.path("link_name").asText(
                                        form.path("form_link_name").asText(""));
                                String formName = form.path("display_name").asText(
                                        form.path("form_name").asText(formLink));
                                if (formLink.isEmpty()) continue;
                                ObjectNode entry = MAPPER.createObjectNode();
                                entry.put("app",          appLink);
                                entry.put("app_name",     appName);
                                entry.put("api_name",     formLink);
                                entry.put("display_name", formName);
                                entry.put("plural_label", formName);
                                formsArr.add(entry);
                            }
                        }
                    } catch (Exception ex) {
                        LOG.log(Level.WARNING, "Could not list forms for app " + appLink, ex);
                    }
                }
            }

            out.put("source", formsArr.size() > 0 ? "live" : "empty");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(out));

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "creator/forms failed", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    static String creatorBase() {
        String dc = System.getenv("ZOHO_DC");
        if (dc == null || dc.trim().isEmpty() || dc.equalsIgnoreCase("com")) {
            return "https://creator.zoho.com/api/v2.1";
        }
        switch (dc.trim().toLowerCase()) {
            case "in": return "https://creator.zoho.in/api/v2.1";
            case "eu": return "https://creator.zoho.eu/api/v2.1";
            case "au": return "https://creator.zoho.com.au/api/v2.1";
            case "jp": return "https://creator.zoho.jp/api/v2.1";
            default:   return "https://creator.zoho.com/api/v2.1";
        }
    }

    static String ownerFromEmail(String email) {
        if (email == null || email.trim().isEmpty()) return "me";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at).toLowerCase() : email.toLowerCase();
    }

    private static String cookieValue(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) { if (name.equals(c.getName())) return c.getValue(); }
        return null;
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
