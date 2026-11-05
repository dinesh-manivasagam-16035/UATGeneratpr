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
import java.util.List;
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
 * Without CRM credentials only an explicit "dry_run": true runs (plans are checked, nothing is sent).
 *
 * Batches: Catalyst stops a request after ~30 s, so the browser sends a few cases at a time with
 * "batch": true. A batch stops starting new cases after EXEC_BATCH_SEC (22) and always runs at
 * least one; cases it didn't reach come back with execution_result.not_reached = true (counted in
 * "not_reached", not "skipped") for the caller to send again. Cases run independently, so a
 * run split into batches gives the same results as one request.
 * "deadline_ms" (absolute epoch ms) caps any run the same way.
 *
 * Background mode (preferred by the browser; batches are the fallback when jobs aren't set up):
 *   {"stage":"submit", cases, platform, dry_run}  → {job_key, job_id?}  queues a "uat_execute"
 *                                                    job on impl_worker (UatJob), one step per case
 *   {"stage":"status", job_key}  → {state, done, total, label?, error?, steps:[{label, result?, ...}]}
 *   {"stage":"result", job_key}  → the /execute response for the job's cases
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
            String stage = body.path("stage").asText("");
            if ("status".equals(stage)) {
                ObjectNode st = JobQueue.status(body.path("job_key").asText(""));
                writeStage(resp, st, st.has("state"));
                return;
            }
            if ("result".equals(stage)) {
                writeStage(resp, result(body.path("job_key").asText("")), false);
                return;
            }
            if (!stage.isEmpty() && !"submit".equals(stage)) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST, "Field 'stage' must be submit, status or result.");
                return;
            }
            JsonNode cases = body.get("cases");
            if (cases == null || !cases.isArray()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Field 'cases' (array) is required.");
                return;
            }

            CrmClient client = client(req, body);
            boolean simulated = !client.hasCredentials();
            if (simulated && !body.path("dry_run").asBoolean(false)) {
                // Never fall back silently: without CRM credentials the caller has to ask
                // for a dry run explicitly, and the results say so.
                resp.setStatus(409);
                ObjectNode err = MAPPER.createObjectNode();
                err.put("error", "Not connected to Zoho CRM, so nothing can be executed. "
                        + "Connect CRM, or run a dry run to check the execution plans.");
                err.put("needs_auth", true);
                resp.getWriter().write(MAPPER.writeValueAsString(err));
                return;
            }
            if ("submit".equals(stage)) {
                writeStage(resp, submit(body, cases, client, simulated), false);
                return;
            }

            ArrayNode out = MAPPER.createArrayNode();
            // Optional absolute deadline (epoch ms): remaining cases are skipped, not dropped.
            long deadlineMs = body.path("deadline_ms").asLong(0);
            boolean batch = body.path("batch").asBoolean(false);
            if (batch) {
                long batchEnd = System.currentTimeMillis() + envInt("EXEC_BATCH_SEC", 22) * 1000L;
                deadlineMs = deadlineMs > 0 ? Math.min(deadlineMs, batchEnd) : batchEnd;
            }
            int ran = 0;
            for (JsonNode tc : cases) {
                ObjectNode caseCopy = tc.deepCopy();
                ObjectNode result;
                if (deadlineMs > 0 && System.currentTimeMillis() > deadlineMs && !(batch && ran == 0)) {
                    result = MAPPER.createObjectNode();
                    result.put("status", "skipped");
                    result.put("reason", "Time budget used up before this case ran.");
                    result.put("not_reached", true);
                } else {
                    ran++;
                    result = CrmExecutor.execute(tc, client);
                }
                caseCopy.set("execution_result", result);
                out.add(caseCopy);
            }
            // One-shot callers keep counting unreached cases as skipped; a batch reports them apart.
            ObjectNode resBody = summarize(out, simulated, batch);

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(resBody));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "execute failed", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** CRM (or Creator) client from the body, the agent's token, or the session cookie; empty for a dry run. */
    private static CrmClient client(HttpServletRequest req, JsonNode body) {
        String platform = body.path("platform").asText("crm");
        JsonNode crm = body.path("crm");
        CrmClient client = new CrmClient(
                crm.path("api_base").asText(""),
                crm.path("accounts_base").asText(""),
                crm.path("client_id").asText(""),
                crm.path("client_secret").asText(""),
                crm.path("refresh_token").asText("")
        );

        // A Zia Agent call brings its own CRM token (see AgentAuth).
        Object agentClient = req.getAttribute(AgentAuth.ATTR);
        if (agentClient instanceof CrmClient && !"creator".equalsIgnoreCase(platform)) {
            client = (CrmClient) agentClient;
        }

        // Fall back to session-stored credentials if body has none.
        if (!client.hasCredentials()) {
            String sid = cookieValue(req, "tp_crm_sid");
            CrmTokenStore.TokenBundle bundle = CrmTokenStore.get(sid);
            if (bundle != null && !bundle.isExpired() && bundle.hasOAuthCredentials()) {
                if ("creator".equalsIgnoreCase(platform)) {
                    // Use same OAuth token but Creator base URL.
                    client = new CrmClient(
                            CreatorModulesServlet.creatorBase(),
                            bundle.accountsBase,
                            bundle.clientId, bundle.clientSecret, bundle.refreshToken);
                } else {
                    client = bundle.toCrmClient();
                }
            }
        }

        // An explicit dry run never touches CRM, even when credentials are available.
        if (body.path("dry_run").asBoolean(false)) {
            client = new CrmClient("", "", "", "", "");
        }
        return client;
    }

    /**
     * Queues a "uat_execute" job (UatJob). The worker has no session, so it gets an access token
     * valid for the job's whole run (refreshed here if it has under 20 minutes left), never the
     * refresh token; the worker removes it from the cached input when the job ends.
     */
    private static ObjectNode submit(JsonNode body, JsonNode cases, CrmClient client, boolean simulated) throws Exception {
        if (cases.size() == 0) return JobQueue.error(400, "No cases to run.");
        ObjectNode in = MAPPER.createObjectNode();
        in.put("kind", "uat_execute");
        in.set("cases", cases);
        in.put("platform", body.path("platform").asText("crm"));
        in.put("dry_run", simulated);
        if (!simulated) {
            try {
                in.put("access_token", client.accessToken(20 * 60_000L));
                in.put("api_base", client.apiBase());
            } catch (Exception e) {
                return JobQueue.error(502, "Couldn't get a CRM access token for the run: " + ImplementServlet.trim(e.getMessage(), 200));
            }
        }
        return JobQueue.submit(JobStore.newKey(), "uat_execute", in, cases.size());
    }

    /** The /execute response for a finished execution job (cases it didn't reach come back skipped). */
    private static ObjectNode result(String key) throws Exception {
        if (!JobStore.validKey(key)) return JobQueue.error(400, "Invalid job_key.");
        String raw = JobStore.get(ImplJob.inputKey(key));
        if (raw == null) return JobQueue.error(404, "This run has expired. Run the cases again.");
        JsonNode in = MAPPER.readTree(raw);
        JsonNode cases = in.path("cases");
        List<JsonNode> done = UatJob.executed(key, cases.size());
        ArrayNode out = MAPPER.createArrayNode();
        for (int i = 0; i < cases.size(); i++) {
            JsonNode d = done.get(i);
            if (d != null) { out.add(d); continue; }
            ObjectNode copy = cases.get(i).deepCopy();
            ObjectNode r = copy.putObject("execution_result");
            r.put("status", "skipped");
            r.put("reason", "The background run stopped before this case.");
            out.add(copy);
        }
        return summarize(out, in.path("dry_run").asBoolean(false), false);
    }

    /** Counts results into the /execute response. notReachedApart: count not_reached cases separately. */
    private static ObjectNode summarize(ArrayNode out, boolean simulated, boolean notReachedApart) {
        int passed = 0, failed = 0, skipped = 0, plansOk = 0, plansWithIssues = 0, notReached = 0;
        for (JsonNode c : out) {
            JsonNode result = c.path("execution_result");
            if (result.path("not_reached").asBoolean(false)) {
                notReached++;
                if (!notReachedApart) skipped++;
                continue;
            }
            switch (result.path("status").asText("")) {
                case "pass":    passed++;  break;
                case "fail":    failed++;  break;
                case "dry_run":
                    if (result.path("plan_ok").asBoolean(false)) plansOk++; else plansWithIssues++;
                    break;
                default:        skipped++; break;
            }
        }
        ObjectNode resBody = MAPPER.createObjectNode();
        resBody.set("cases", out);
        resBody.put("simulated", simulated);
        resBody.put("passed", passed);
        resBody.put("failed", failed);
        resBody.put("skipped", skipped);
        resBody.put("not_reached", notReached);
        resBody.put("dry_run", simulated);
        if (simulated) {
            resBody.put("plans_ok", plansOk);
            resBody.put("plans_with_issues", plansWithIssues);
        }
        return resBody;
    }

    /** Writes a stage response: {status, error} → that HTTP status, unless the error is the job's own (reported). */
    private static void writeStage(HttpServletResponse resp, ObjectNode out, boolean reported) throws IOException {
        if (out.has("error") && !reported) resp.setStatus(out.path("status").asInt(400));
        else resp.setStatus(200);
        out.remove("status");
        resp.getWriter().write(MAPPER.writeValueAsString(out));
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

    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
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
