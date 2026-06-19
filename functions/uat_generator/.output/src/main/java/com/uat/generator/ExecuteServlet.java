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
 * POST /execute
 *
 * Body: {
 *   "cases": [ ... LLM-generated UAT cases with execution_plan ... ],
 *   "crm": {
 *     "api_base":      "https://www.zohoapis.com",      (optional)
 *     "accounts_base": "https://accounts.zoho.com",     (optional)
 *     "org_id":        "60012345678",                    (optional; informational)
 *     "client_id":     "...",
 *     "client_secret": "...",
 *     "refresh_token": "..."
 *   }
 * }
 *
 * Returns each case with an `execution_result` field appended.
 * If CRM credentials are missing, executor runs in simulated mode.
 */
public class ExecuteServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ExecuteServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            JsonNode cases = body.get("cases");
            if (cases == null || !cases.isArray()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Field 'cases' (array) is required.");
                return;
            }

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

            boolean simulated = !client.hasCredentials();
            ArrayNode out = MAPPER.createArrayNode();
            int passed = 0, failed = 0, skipped = 0;

            for (JsonNode tc : cases) {
                ObjectNode caseCopy = tc.deepCopy();
                ObjectNode result = CrmExecutor.execute(tc, client);
                caseCopy.set("execution_result", result);
                out.add(caseCopy);
                String status = result.path("status").asText("");
                switch (status) {
                    case "pass":    passed++;  break;
                    case "fail":    failed++;  break;
                    default:        skipped++; break;
                }
            }

            ObjectNode resBody = MAPPER.createObjectNode();
            resBody.set("cases", out);
            resBody.put("simulated", simulated);
            resBody.put("passed", passed);
            resBody.put("failed", failed);
            resBody.put("skipped", skipped);

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(resBody));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "execute failed", e);
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

    private static String cookieValue(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
