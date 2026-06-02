package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /functions/list
 *
 * Body: { "crm": { api_base, accounts_base, client_id, client_secret, refresh_token } }
 *
 * Returns: { "functions": [ { name, display_name, language, category,
 *                            modified_time, status } ], "source": "live" }
 *
 * Uses GET /crm/v3/settings/functions on the user's CRM org.
 *
 * Required CRM scope: ZohoCRM.functions.READ (or ZohoCRM.settings.functions.READ).
 */
public class FunctionsListServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(FunctionsListServlet.class.getName());
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

            if (!client.hasCredentials()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "CRM credentials required. Fill the connection panel on the Brief tab.");
                return;
            }

            CrmClient.Response r = client.call("GET", "/crm/v3/settings/functions", null);
            if (r.statusCode >= 400 || r.json == null) {
                writeError(resp,
                        r.statusCode == 0 ? HttpServletResponse.SC_BAD_GATEWAY : r.statusCode,
                        "CRM functions fetch failed: "
                                + (r.rawBody == null ? "(no body)" : r.rawBody));
                return;
            }

            ArrayNode out = MAPPER.createArrayNode();
            JsonNode functions = r.json.path("functions");
            if (functions.isArray()) {
                for (JsonNode f : functions) {
                    ObjectNode o = MAPPER.createObjectNode();
                    o.put("name", f.path("function_name").asText(f.path("name").asText("")));
                    o.put("display_name", f.path("display_name").asText(""));
                    o.put("language", f.path("language").asText("deluge"));
                    o.put("category", f.path("category").asText(""));
                    o.put("description", f.path("description").asText(""));
                    o.put("modified_time", f.path("modified_time").asText(""));
                    o.put("id", f.path("id").asText(""));
                    JsonNode args = f.path("arguments");
                    if (args.isArray()) {
                        ArrayNode argList = o.putArray("arguments");
                        args.forEach(a -> {
                            ObjectNode ao = argList.addObject();
                            ao.put("name", a.path("name").asText(""));
                            ao.put("type", a.path("type").asText("string"));
                        });
                    }
                    out.add(o);
                }
            }

            ObjectNode body2 = MAPPER.createObjectNode();
            body2.set("functions", out);
            body2.put("count", out.size());
            body2.put("source", "live");

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(body2));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "functions list failed", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
