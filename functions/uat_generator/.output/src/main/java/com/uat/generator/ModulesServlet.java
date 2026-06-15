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
 * POST /modules
 *
 * Body: {
 *   "crm": {
 *     "api_base":      "https://www.zohoapis.in",          (optional)
 *     "accounts_base": "https://accounts.zoho.in",         (optional)
 *     "client_id":     "...",
 *     "client_secret": "...",
 *     "refresh_token": "..."
 *   }
 * }
 *
 * Returns: { "modules": [ { api_name, plural_label, singular_label, creatable,
 *                           editable, deletable, viewable, custom } ] }
 *
 * Falls back to a built-in default list when credentials are not provided —
 * lets the UI populate even before the user enters OAuth creds.
 */
public class ModulesServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ModulesServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            JsonNode crm = body.path("crm");
            CrmClient client = new CrmClient(
                    crm.path("api_base").asText(""),
                    crm.path("accounts_base").asText(""),
                    crm.path("client_id").asText(""),
                    crm.path("client_secret").asText(""),
                    crm.path("refresh_token").asText("")
            );

            // Fall back to session-stored credentials if body has none.
            if (!client.hasCredentials()) {
                String sid = cookieValue(req, "tp_crm_sid");
                CrmTokenStore.TokenBundle bundle = CrmTokenStore.get(sid);
                if (bundle != null && !bundle.isExpired() && bundle.hasOAuthCredentials()) {
                    client = bundle.toCrmClient();
                }
            }

            ObjectNode out = MAPPER.createObjectNode();

            if (!client.hasCredentials()) {
                out.set("modules", defaultModules());
                out.put("source", "default");
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(MAPPER.writeValueAsString(out));
                return;
            }

            CrmClient.Response r = client.call("GET", "/crm/v3/settings/modules", null);
            if (r.statusCode >= 400 || r.json == null) {
                writeError(resp, r.statusCode == 0
                                ? HttpServletResponse.SC_BAD_GATEWAY
                                : r.statusCode,
                        "CRM modules fetch failed: " + (r.rawBody == null ? "(no body)" : r.rawBody));
                return;
            }

            ArrayNode trimmed = MAPPER.createArrayNode();
            JsonNode modules = r.json.path("modules");
            if (modules.isArray()) {
                for (JsonNode m : modules) {
                    if (!m.path("api_supported").asBoolean(true)) continue;
                    if (m.path("api_name").asText("").isEmpty()) continue;
                    ObjectNode o = MAPPER.createObjectNode();
                    o.put("api_name", m.path("api_name").asText(""));
                    o.put("plural_label", m.path("plural_label").asText(""));
                    o.put("singular_label", m.path("singular_label").asText(""));
                    o.put("module_name", m.path("module_name").asText(""));
                    o.put("creatable", m.path("creatable").asBoolean(false));
                    o.put("editable", m.path("editable").asBoolean(false));
                    o.put("deletable", m.path("deletable").asBoolean(false));
                    o.put("viewable", m.path("viewable").asBoolean(false));
                    o.put("custom", m.path("generated_type").asText("").equalsIgnoreCase("custom"));
                    trimmed.add(o);
                }
            }
            out.set("modules", trimmed);
            out.put("source", "live");
            out.put("count", trimmed.size());

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "modules fetch failed", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    /** A reasonable default set for users who haven't connected to CRM yet. */
    private static ArrayNode defaultModules() {
        ArrayNode arr = MAPPER.createArrayNode();
        addDefault(arr, "Leads",    "Leads",    "Lead");
        addDefault(arr, "Contacts", "Contacts", "Contact");
        addDefault(arr, "Accounts", "Accounts", "Account");
        addDefault(arr, "Deals",    "Deals",    "Deal");
        addDefault(arr, "Tasks",    "Tasks",    "Task");
        addDefault(arr, "Cases",    "Cases",    "Case");
        addDefault(arr, "Products", "Products", "Product");
        addDefault(arr, "Quotes",   "Quotes",   "Quote");
        return arr;
    }

    private static void addDefault(ArrayNode arr, String apiName, String plural, String singular) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("api_name", apiName);
        o.put("plural_label", plural);
        o.put("singular_label", singular);
        o.put("module_name", apiName);
        o.put("creatable", true);
        o.put("editable", true);
        o.put("deletable", true);
        o.put("viewable", true);
        o.put("custom", false);
        arr.add(o);
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }

    private static String cookieValue(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
