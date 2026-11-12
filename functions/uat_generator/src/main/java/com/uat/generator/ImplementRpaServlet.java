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
            JsonNode body = MAPPER.readTree(req.getInputStream());
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
                int code = push(hook.trim(), job);
                out.put("pushed", code >= 200 && code < 300);
                out.put("push_status", code);
                stored.put("pushed", code >= 200 && code < 300);
                JobStore.put(jobId, MAPPER.writeValueAsString(stored));
            } catch (Exception e) {
                out.put("pushed", false);
                out.put("push_error", ImplementServlet.trim(e.getMessage(), 300));
            }
        } else {
            out.put("pushed", false);
            out.put("push_error", "RPA_WEBHOOK_URL isn't set on uat_generator; download the job and start the bot by hand.");
        }
        return out;
    }

    static int push(String url, ObjectNode job) throws Exception {
        RequestConfig cfg = RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(15)).setConnectionRequestTimeout(Timeout.ofSeconds(10)).build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            HttpPost post = new HttpPost(url);
            String tok = System.getenv("RPA_WEBHOOK_TOKEN");
            if (tok != null && !tok.isEmpty()) post.setHeader("X-LiftOff-Token", tok);
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(job), ContentType.APPLICATION_JSON));
            return http.execute(post, r -> { EntityUtils.consume(r.getEntity()); return r.getCode(); });
        }
    }

    static ObjectNode result(JsonNode body) throws Exception {
        String jobId = body.path("job_id").asText("");
        if (!JobStore.validKey(jobId)) return err(400, "Unknown job_id.");
        String raw = JobStore.get(jobId);
        if (raw == null) return err(404, "This job has expired.");
        ObjectNode stored = (ObjectNode) MAPPER.readTree(raw);
        if (!stored.path("token").asText().equals(body.path("token").asText("-"))) return err(403, "Bad token.");
        ArrayNode results = stored.withArray("results");
        for (JsonNode r : body.path("results")) {
            String id = r.path("id").asText("");
            String st = r.path("status").asText("").toLowerCase();
            if (id.isEmpty() || !(st.equals("done") || st.equals("failed") || st.equals("skipped"))) continue;
            // last result per item wins
            for (int i = results.size() - 1; i >= 0; i--) if (results.get(i).path("id").asText().equals(id)) results.remove(i);
            ObjectNode o = results.addObject();
            o.put("id", id); o.put("status", st);
            o.put("note", r.path("note").asText(""));
            o.put("screenshot_url", r.path("screenshot_url").asText(""));
            o.put("at", Instant.now().toString());
        }
        JobStore.put(jobId, MAPPER.writeValueAsString(stored));
        ObjectNode out = MAPPER.createObjectNode();
        out.put("ok", true);
        out.put("recorded", results.size());
        return out;
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
