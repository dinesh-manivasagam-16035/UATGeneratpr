package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /functions/run
 *
 * Body: {
 *   "name":      "my_function",                       (function api_name)
 *   "arguments": { "key": "value", ... }              (optional; passed as query params)
 *   "crm":       { api_base, accounts_base, client_id, client_secret, refresh_token }
 * }
 *
 * Calls POST /crm/v3/functions/{name}/actions/execute and returns:
 *   {
 *     "name":        "my_function",
 *     "status":      "ok" | "error",
 *     "status_code": 200,
 *     "duration_ms": 412,
 *     "response":    { ...raw CRM response... },
 *     "logs":        [ ...rough log lines parsed from response... ],
 *     "error":       "..."  (when status == error)
 *   }
 *
 * Required CRM scope: ZohoCRM.functions.execute.READ (or ZohoCRM.functions.ALL).
 *
 * Note: Zoho CRM v3 doesn't expose persisted function execution logs via API
 * (they live in Setup > Functions > Execution Log). We surface what we can:
 * the API response body, output, error details, plus an HTTP-level status.
 */
public class FunctionRunServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(FunctionRunServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String name = body.path("name").asText("").trim();
            if (name.isEmpty()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Field 'name' (function name) is required.");
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

            if (!client.hasCredentials()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "CRM credentials required. Fill the connection panel on the Brief tab.");
                return;
            }

            // Build the execute URL. Query args (if any) go in the URL.
            StringBuilder path = new StringBuilder("/crm/v3/functions/")
                    .append(name).append("/actions/execute");
            JsonNode args = body.path("arguments");
            if (args.isObject() && args.size() > 0) {
                boolean first = true;
                java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = args.fields();
                while (it.hasNext()) {
                    java.util.Map.Entry<String, JsonNode> e = it.next();
                    path.append(first ? '?' : '&').append(urlEncode(e.getKey()))
                        .append('=').append(urlEncode(e.getValue().asText("")));
                    first = false;
                }
            }

            long t0 = System.currentTimeMillis();
            CrmClient.Response r;
            String execError = null;
            try {
                r = client.call("POST", path.toString(), null);
            } catch (Exception ex) {
                long dt = System.currentTimeMillis() - t0;
                ObjectNode out = MAPPER.createObjectNode();
                out.put("name", name);
                out.put("status", "error");
                out.put("status_code", 0);
                out.put("duration_ms", dt);
                out.put("error", ex.getMessage());
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(MAPPER.writeValueAsString(out));
                return;
            }
            long dt = System.currentTimeMillis() - t0;

            ObjectNode out = MAPPER.createObjectNode();
            out.put("name", name);
            out.put("status_code", r.statusCode);
            out.put("duration_ms", dt);
            boolean ok = r.statusCode >= 200 && r.statusCode < 300;
            out.put("status", ok ? "ok" : "error");

            if (r.json != null) {
                out.set("response", r.json);
                // Try to surface CRM's error details cleanly
                JsonNode codeNode = r.json.path("code");
                JsonNode messageNode = r.json.path("message");
                if (!ok || (!codeNode.isMissingNode() && !"SUCCESS".equals(codeNode.asText("")))) {
                    StringBuilder err = new StringBuilder();
                    if (!codeNode.isMissingNode()) err.append(codeNode.asText("")).append(": ");
                    if (!messageNode.isMissingNode()) err.append(messageNode.asText(""));
                    if (err.length() > 0) {
                        execError = err.toString();
                        out.put("status", "error");
                    }
                }
                JsonNode details = r.json.path("details");
                if (details.isObject() || details.isArray()) {
                    out.set("details", details);
                }
                JsonNode output = r.json.path("output");
                if (!output.isMissingNode() && !output.isNull()) {
                    out.set("output", output);
                }
            } else if (r.rawBody != null && !r.rawBody.isEmpty()) {
                out.put("raw_response", r.rawBody);
            }
            if (execError != null) out.put("error", execError);

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "function run failed", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception ex) {
            return s == null ? "" : s;
        }
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
