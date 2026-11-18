package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.time.Instant;

/**
 * The UI-work queue as a job for an external runner — Zoho RPA first. The recipe format is the
 * contract (see ImplRpa.recipe): the bot performs each op and reports back per item.
 *
 *   POST /implement/rpa-job     {org, items:[{id, section, label, module, steps, recipe}], runner?}
 *                               → {job_id, job, pushed, push_status, push_error}
 *                               The job is stored for 24 h and, when RPA_WEBHOOK_URL is set, POSTed there
 *                               (header X-LiftOff-Token: RPA_WEBHOOK_TOKEN when set). Otherwise the browser
 *                               downloads it for a bot that polls or reads a file.
 *   POST /implement/rpa-result  {job_id, token, results:[{id, status (done|failed|skipped), note?, screenshot_url?}]}
 *                               Called by the bot (no session cookie); the token is the one inside the job.
 *   POST /implement/rpa-status  {job_id} → {job_id, created_at, pushed, results:[...], finished}
 */
public class ImplementRpaServlet extends HttpServlet {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RNG = new SecureRandom();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);
        String path = req.getPathInfo() == null ? req.getRequestURI() : req.getPathInfo();
        try {
            JsonNode body = readBody(req);
            ObjectNode out;
            if (path.endsWith("/rpa-result")) out = result(body);
            else if (path.endsWith("/rpa-status")) out = status(body);
            else out = create(req, body);
            if (out.has("_status")) { resp.setStatus(out.path("_status").asInt()); out.remove("_status"); }
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            ImplementServlet.writeError(resp, 500, "RPA job: " + ImplementServlet.trim(e.getMessage(), 300));
        }
    }

    /**
     * The request as JSON. A caller that can only send form fields or query parameters (a Zoho Flow
     * webhook step, an RPA "HTTP request" card) may post job_id, token, id, status, note and all that way.
     */
    static JsonNode readBody(HttpServletRequest req) throws IOException {
        JsonNode body = null;
        try { body = MAPPER.readTree(req.getInputStream()); } catch (Exception ignored) { /* not JSON: fall back to parameters */ }
        if (body != null && body.isObject()) return body;
        ObjectNode o = MAPPER.createObjectNode();
        for (String k : new String[]{"job_id", "token", "id", "status", "note", "screenshot_url", "all"}) {
            String v = req.getParameter(k);
            if (v != null) o.put(k, v);
        }
        return o;
    }

    static boolean validStatus(String st) { return st.equals("done") || st.equals("failed") || st.equals("skipped"); }

    /**
     * Records what the bot reports on a stored job. Three shapes, from most to least detailed:
     *   results:[{id, status, note?, screenshot_url?}]   one entry per item (several per call are fine)
     *   id + status (+ note, screenshot_url)             one item, flat — easiest to build in a flow step
     *   all: done|failed|skipped (+ note)                every item that has no result yet — for a bot that
     *                                                    runs the whole job as one action and reports once
     * The last result per item wins. Returns how many results the call recorded.
     */
    static int applyResults(ObjectNode stored, JsonNode body) {
        ArrayNode results = stored.withArray("results");
        int n = 0;
        ArrayNode incoming = MAPPER.createArrayNode();
        for (JsonNode r : body.path("results")) incoming.add(r);
        if (!body.path("id").asText("").isEmpty()) {
            incoming.addObject().put("id", body.path("id").asText()).put("status", body.path("status").asText(""))
                    .put("note", body.path("note").asText("")).put("screenshot_url", body.path("screenshot_url").asText(""));
        }
        String all = body.path("all").asText("").toLowerCase();
        if (validStatus(all)) {
            java.util.Set<String> have = new java.util.HashSet<>();
            for (JsonNode r : results) have.add(r.path("id").asText());
            for (JsonNode r : incoming) have.add(r.path("id").asText());
            for (JsonNode it : stored.path("job").path("items")) {
                String id = it.path("id").asText("");
                if (!id.isEmpty() && !have.contains(id)) incoming.addObject().put("id", id).put("status", all).put("note", body.path("note").asText(""));
            }
        }
        for (JsonNode r : incoming) {
            String id = r.path("id").asText("");
            String st = r.path("status").asText("").toLowerCase();
            if (id.isEmpty() || !validStatus(st)) continue;
            for (int i = results.size() - 1; i >= 0; i--) if (results.get(i).path("id").asText().equals(id)) results.remove(i);
            ObjectNode o = results.addObject();
            o.put("id", id); o.put("status", st);
            o.put("note", r.path("note").asText(""));
            o.put("screenshot_url", r.path("screenshot_url").asText(""));
            o.put("at", Instant.now().toString());
            n++;
        }
        return n;
    }

    static ObjectNode create(HttpServletRequest req, JsonNode body) throws Exception {
        JsonNode items = body.path("items");
        if (!items.isArray() || items.size() == 0) return err(400, "Field 'items' is required (the UI-work queue).");
        String jobId = JobStore.newKey();
        String token = token();
        String base = publicBase(req);

        ObjectNode job = MAPPER.createObjectNode();
        job.put("job_id", jobId);
        job.put("created_at", Instant.now().toString());
        job.put("product", Products.use(body).id());
        job.put("runner", body.path("runner").asText("zoho-rpa"));
        job.set("org", body.path("org").isObject() ? body.path("org") : MAPPER.createObjectNode());
        job.put("setup_url", setupUrl(req, job.path("org"), job.path("product").asText("crm")));
        ObjectNode cb = job.putObject("callback");
        cb.put("url", base + "/implement/rpa-result");
        cb.put("token", token);
        cb.put("method", "POST");
        cb.put("body_example", "{\"job_id\":\"" + jobId + "\",\"token\":\"" + token + "\",\"results\":[{\"id\":\"VR-1\",\"status\":\"done\",\"note\":\"\",\"screenshot_url\":\"\"}]}");
        // Ready to send as they are: one call when the whole job ran, or one per item (see items[].report_done).
        cb.put("finish_body", "{\"job_id\":\"" + jobId + "\",\"token\":\"" + token + "\",\"all\":\"done\"}");
        cb.put("failed_body", "{\"job_id\":\"" + jobId + "\",\"token\":\"" + token + "\",\"all\":\"failed\",\"note\":\"the bot stopped\"}");
        ObjectNode ops = job.putObject("recipe_ops");
        ops.put("goto",    "open setup_url + {path} (e.g. setup_url + /settings/modules/Leads/validation-rules)");
        ops.put("click",   "click the control whose visible text matches {text} (regex, case-insensitive; '|' = alternatives)");
        ops.put("fill",    "type {value} into the field whose label or placeholder matches {label}");
        ops.put("choose",  "open the dropdown near {near} and pick {option}");
        ops.put("type",    "paste {value} into the code editor");
        ops.put("confirm", "pause for a human approval showing {text}; continue only when approved");
        ops.put("expect",  "verify {text} is visible; report failed when it isn't");
        // Approval point: by default the bot pauses before every Save ("confirm" ops). With RPA_AUTO_APPROVE=1
        // (or {"auto_approve": true} in the request) those ops are dropped — the bot runs straight through and
        // the person approves afterwards on the Build step, which stays the gate before Verify and UAT.
        boolean auto = "1".equals(System.getenv("RPA_AUTO_APPROVE")) || body.path("auto_approve").asBoolean(false);
        job.put("auto_approve", auto);
        ArrayNode its = job.putArray("items");
        for (JsonNode it : items) {
            ObjectNode o = its.addObject();
            o.put("id", it.path("id").asText());
            o.put("section", it.path("section").asText());
            o.put("label", it.path("label").asText());
            o.put("module", it.path("module").asText(""));
            o.set("steps", it.path("steps").isArray() ? it.path("steps") : MAPPER.createArrayNode());
            ArrayNode recipe = MAPPER.createArrayNode();
            for (JsonNode op : it.path("recipe")) if (!(auto && "confirm".equals(op.path("op").asText()))) recipe.add(op);
            o.set("recipe", recipe);
            o.put("mode", it.path("recipe").size() > 0 ? "auto" : "manual");
            if (it.has("code")) o.put("code", it.path("code").asText());
            String idJson = MAPPER.writeValueAsString(it.path("id").asText());
            o.put("report_done", "{\"job_id\":\"" + jobId + "\",\"token\":\"" + token + "\",\"id\":" + idJson + ",\"status\":\"done\"}");
            o.put("report_failed", "{\"job_id\":\"" + jobId + "\",\"token\":\"" + token + "\",\"id\":" + idJson + ",\"status\":\"failed\"}");
        }

        ObjectNode stored = MAPPER.createObjectNode();
        stored.set("job", job);
        stored.put("token", token);
        stored.putArray("results");
        JobStore.put(jobId, MAPPER.writeValueAsString(stored));

        ObjectNode out = MAPPER.createObjectNode();
        out.put("job_id", jobId);
        out.set("job", job);
        String hook = System.getenv("RPA_WEBHOOK_URL");
        if (hook != null && !hook.trim().isEmpty()) {
            try {
                String[] res = push(hook.trim(), job);
                int code = Integer.parseInt(res[0]);
                boolean ok = code >= 200 && code < 300;
                out.put("pushed", ok);
                out.put("push_status", code);
                if (!ok) out.put("push_error", pushProblem(code, res[1]));
                stored.put("pushed", ok);
                JobStore.put(jobId, MAPPER.writeValueAsString(stored));
            } catch (Exception e) {
                out.put("pushed", false);
                out.put("push_error", "Couldn't reach the RPA webhook: " + ImplementServlet.trim(e.getMessage(), 200)
                        + ". Check that RPA_WEBHOOK_URL on uat_generator is the flow's current webhook URL.");
            }
        } else {
            out.put("pushed", false);
            out.put("push_error", "RPA_WEBHOOK_URL isn't set on uat_generator. Add the flow's webhook URL as that environment variable in the Catalyst console and redeploy the function.");
        }
        return out;
    }

    /** Why the webhook refused the job, in words a person can act on. Never echoes the URL (it carries a key). */
    static String pushProblem(int code, String body) {
        String b = body == null ? "" : body.toLowerCase();
        String what;
        if (code == 410 || b.contains("invalid oauth") || b.contains("zapikey")) {
            what = "the webhook key in RPA_WEBHOOK_URL is no longer valid. Copy the current URL from the flow's Webhook trigger into RPA_WEBHOOK_URL and redeploy uat_generator";
        } else if (code == 404) {
            what = "the webhook address wasn't found. The flow may have been deleted or RPA_WEBHOOK_URL is mistyped";
        } else if (code == 401 || code == 403) {
            what = "the webhook refused the call. Check the key in RPA_WEBHOOK_URL and, if set, RPA_WEBHOOK_TOKEN";
        } else if (code == 400) {
            what = "the flow didn't accept the job. Make sure the flow is switched on and has at least one action after the trigger";
        } else if (code >= 500) {
            what = "Zoho RPA answered with a server error. Try again in a minute";
        } else {
            what = "the flow didn't accept the job";
        }
        String detail = body == null || body.trim().isEmpty() ? "" : " Zoho said: " + ImplementServlet.trim(body.replaceAll("\\s+", " ").trim(), 160);
        return "Zoho RPA answered HTTP " + code + ": " + what + "." + detail;
    }


    /** Posts the job to the flow's webhook; returns {status code, response body}. */
    static String[] push(String url, ObjectNode job) throws Exception {
        RequestConfig cfg = RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(15)).setConnectionRequestTimeout(Timeout.ofSeconds(10)).build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            HttpPost post = new HttpPost(url);
            String tok = System.getenv("RPA_WEBHOOK_TOKEN");
            if (tok != null && !tok.isEmpty()) post.setHeader("X-LiftOff-Token", tok);
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(job), ContentType.APPLICATION_JSON));
            return http.execute(post, r -> new String[]{String.valueOf(r.getCode()), r.getEntity() == null ? "" : EntityUtils.toString(r.getEntity())});
        }
    }

    static ObjectNode result(JsonNode body) throws Exception {
        String jobId = body.path("job_id").asText("");
        if (!JobStore.validKey(jobId)) return err(400, "Unknown job_id.");
        String raw = JobStore.get(jobId);
        if (raw == null) return err(404, "This job has expired.");
        ObjectNode stored = (ObjectNode) MAPPER.readTree(raw);
        if (!stored.path("token").asText().equals(body.path("token").asText("-"))) return err(403, "Bad token.");
        int recorded = applyResults(stored, body);
        if (recorded == 0) return err(400, "Nothing to record: send results:[{id,status}], or id + status, or all=done|failed|skipped. "
                + "Item ids of this job: " + itemIds(stored) + ".");
        ArrayNode results = stored.withArray("results");
        JobStore.put(jobId, MAPPER.writeValueAsString(stored));
        ObjectNode out = MAPPER.createObjectNode();
        out.put("ok", true);
        out.put("recorded", results.size());
        out.put("of", stored.path("job").path("items").size());
        return out;
    }

    static String itemIds(JsonNode stored) {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode it : stored.path("job").path("items")) ids.add(it.path("id").asText());
        return String.join(", ", ids);
    }

    static ObjectNode status(JsonNode body) throws Exception {
        String jobId = body.path("job_id").asText("");
        if (!JobStore.validKey(jobId)) return err(400, "Unknown job_id.");
        String raw = JobStore.get(jobId);
        if (raw == null) return err(404, "This job has expired.");
        JsonNode stored = MAPPER.readTree(raw);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("job_id", jobId);
        out.put("created_at", stored.path("job").path("created_at").asText(""));
        out.put("pushed", stored.path("pushed").asBoolean(false));
        out.set("results", stored.path("results"));
        int total = stored.path("job").path("items").size();
        out.put("finished", total > 0 && stored.path("results").size() >= total);
        return out;
    }

    /** The org's Setup base: https://crm.zoho.<dc>/crm/<org domain name> (falls back to /crm/org<zgid>, then /crm). */
    static String setupUrl(HttpServletRequest req, JsonNode org, String product) {
        String dc = ".com";
        try {
            CrmClient c = CrmMetadata.sessionClient(req);
            if (c != null) {
                String api = c.apiBase();
                String t = api.replaceAll("^https?://[^.]+\\.zohoapis", "").replaceAll("/.*$", "");
                if (t.startsWith(".")) dc = t;
            }
        } catch (Exception ignored) { /* default dc */ }
        String env = System.getenv("ZOHO_DC");
        if (env != null && !env.trim().isEmpty() && !env.trim().equalsIgnoreCase("com")) dc = "." + env.trim().replaceFirst("^\\.", "");
        if ("desk".equals(product)) return "https://desk.zoho" + dc;
        String domain = org.path("domain").asText("");
        String zgid = org.path("zgid").asText("");
        String seg = !domain.isEmpty() ? domain : !zgid.isEmpty() ? "org" + zgid : "";
        return "https://crm.zoho" + dc + "/crm" + (seg.isEmpty() ? "" : "/" + seg);
    }

    private static String token() {
        String abc = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) sb.append(abc.charAt(RNG.nextInt(abc.length())));
        return sb.toString();
    }

    /** Public base of this function, for the bot's callback URL. */
    private static String publicBase(HttpServletRequest req) {
        String env = System.getenv("PUBLIC_FUNCTION_BASE");
        if (env != null && !env.trim().isEmpty()) return env.trim().replaceAll("/+$", "");
        String origin = req.getHeader("Origin");
        if (origin != null && !origin.isEmpty()) return origin.replaceAll("/+$", "") + "/server/uat_generator";
        return req.getScheme() + "://" + req.getServerName() + "/server/uat_generator";
    }

    private static ObjectNode err(int status, String msg) {
        return MAPPER.createObjectNode().put("_status", status).put("error", msg);
    }
}
