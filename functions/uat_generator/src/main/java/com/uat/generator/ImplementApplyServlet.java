package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /implement/apply — step 3 of the implementation pipeline (Executor).
 *
 * Body: {
 *   "spec": { ...from /implement, after review... },
 *   "dry_run": true,                 // default true: nothing is written, requests are previewed
 *   "confirm_org_id": "6000...",     // required for a real run; must match the connected org
 *   "item_ids": ["MOD-1","FLD-2"]    // optional subset; default = every included item
 * }
 * A real run also requires spec.approved == true (set in the UI after human review), and
 * only ever writes to a sandbox org: production is read-only here (dry runs still work).
 *
 * What runs where:
 *   api / partial — custom modules, custom fields, roles: created through the CRM settings REST APIs
 *          (partial items also get a "finish by hand" entry in rpa_queue),
 *          in that order (fields can target modules created in the same run).
 *   rpa  — layouts, validation rules, workflows/blueprints/assignment/approvals, Deluge
 *          functions, and field types the field API doesn't create: returned in rpa_queue
 *          with step-by-step instructions for an RPA bot or a consultant.
 * Every item is re-compared with the org at apply time (ImplCompare): already there →
 * skipped; a picklist missing values → those values are added (PATCH); a type or lookup
 * conflict → result "conflict" and nothing is sent. Every item gets a result line;
 * failures don't stop the run.
 *
 * Env: CRM_SETTINGS_API_VERSION (v8) — API version used for the create/update calls.
 */
public class ImplementApplyServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ImplementApplyServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);
        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            JsonNode spec = body.path("spec");
            if (!spec.isObject()) {
                ImplementServlet.writeError(resp, 400, "Field 'spec' (object from /implement) is required.");
                return;
            }
            boolean dryRun = body.path("dry_run").asBoolean(true);
            Set<String> only = new HashSet<>();
            for (JsonNode id : body.path("item_ids")) only.add(id.asText());

            boolean mock = "mock".equalsIgnoreCase(System.getenv().getOrDefault("LLM_PROVIDER", ""));
            CrmClient crm = CrmMetadata.sessionClient(req);
            if (crm == null && !(mock && dryRun)) {
                ImplementServlet.writeError(resp, 401, "Connect Zoho CRM first — apply needs a signed-in org.");
                return;
            }

            // A single-step run (item_ids) only reads the modules those items touch.
            ImplOrg org = crm == null ? null : ImplOrg.read(crm, only.isEmpty() ? modulesOf(spec) : modulesOf(spec, only), false);
            if (!dryRun) {
                if (!spec.path("approved").asBoolean(false)) {
                    ImplementServlet.writeError(resp, 409, "The spec hasn't been approved. Review it and click Approve before applying.");
                    return;
                }
                if (org.orgId.isEmpty()) {
                    ImplementServlet.writeError(resp, 502, "Couldn't read the connected org's id, so apply was not attempted.");
                    return;
                }
                if (!org.orgId.equals(body.path("confirm_org_id").asText(""))) {
                    ImplementServlet.writeError(resp, 409, "confirm_org_id doesn't match the connected org ("
                            + org.orgName + ", " + org.orgId + "). Nothing was changed.");
                    return;
                }
                if (!org.sandbox) {
                    ImplementServlet.writeError(resp, 403, "\"" + org.orgName + "\" isn't a sandbox org (type: "
                            + (org.orgType.isEmpty() ? "unknown" : org.orgType) + "). LiftOff only builds in sandbox; "
                            + "production is updated by promoting a verified build. Nothing was changed.");
                    return;
                }
            }

            Run run = new Run(crm, org, dryRun);
            ArrayNode results = MAPPER.createArrayNode();
            ArrayNode rpaQueue = MAPPER.createArrayNode();

            for (String section : new String[]{"modules", "fields", "roles", "layouts", "validation_rules",
                                               "workflows", "functions"}) {
                for (JsonNode item : spec.path(section)) {
                    String id = item.path("id").asText();
                    if (!only.isEmpty() && !only.contains(id)) continue;
                    if (!item.path("include").asBoolean(true)) continue;
                    ObjectNode r = results.addObject();
                    r.put("id", id);
                    r.put("section", section);
                    r.put("label", ImplPlan.describe(section, item));
                    r.put("executor", item.path("executor").asText());
                    if ("exists".equals(item.path("status").asText())) {
                        r.put("result", "skipped");
                        r.put("detail", "Already in the org.");
                        continue;
                    }
                    String executor = item.path("executor").asText();
                    if ("api".equals(executor) || "partial".equals(executor)) {
                        try {
                            run.apply(section, item, r);
                        } catch (Exception e) {
                            r.put("result", "failed");
                            r.put("detail", ImplementServlet.trim(e.getMessage(), 400));
                        }
                        if ("partial".equals(executor)) {
                            ObjectNode q = rpaQueue.addObject();
                            q.put("id", id);
                            q.put("section", section);
                            q.put("label", "Finish " + ImplPlan.describe(section, item));
                            q.put("partial", true);
                            ArrayNode steps = q.putArray("steps");
                            ImplRpa.followUp(section, item).forEach(steps::add);
                            q.set("item", item);
                        }
                    } else if ("rpa".equals(item.path("executor").asText())) {
                        r.put("result", "queued_for_rpa");
                        ObjectNode q = rpaQueue.addObject();
                        q.put("id", id);
                        q.put("section", section);
                        q.put("label", ImplPlan.describe(section, item));
                        ArrayNode steps = q.putArray("steps");
                        ImplRpa.steps(section, item).forEach(steps::add);
                        q.set("item", item);
                        r.put("detail", "No reliable create API; " + steps.size() + " UI step(s) queued.");
                    } else {
                        r.put("result", "skipped");
                        r.put("detail", "Nothing to build.");
                    }
                }
            }

            ObjectNode out = MAPPER.createObjectNode();
            out.put("dry_run", dryRun);
            if (org != null) {
                ObjectNode o = out.putObject("org");
                o.put("id", org.orgId);
                o.put("name", org.orgName);
                o.put("type", org.orgType);
                o.put("sandbox", org.sandbox);
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (JsonNode r : results) counts.merge(r.path("result").asText(), 1, Integer::sum);
            ObjectNode c = out.putObject("counts");
            counts.forEach(c::put);
            out.set("results", results);
            out.set("rpa_queue", rpaQueue);
            resp.setStatus(200);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "implement/apply failed", e);
            ImplementServlet.writeError(resp, 500, e.getMessage());
        }
    }

    /** Module names the given items refer to (their own module and lookup target). */
    static Set<String> modulesOf(JsonNode spec, Set<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String section : new String[]{"modules", "fields", "layouts", "validation_rules", "workflows", "functions"}) {
            for (JsonNode item : spec.path(section)) {
                if (!ids.contains(item.path("id").asText())) continue;
                out.add("modules".equals(section) ? item.path("api_name").asText() : item.path("module").asText());
                out.add(item.path("lookup_module").asText(""));
            }
        }
        out.remove("");
        return out;
    }

    /** Module names a spec refers to, for reading the org. */
    static Set<String> modulesOf(JsonNode spec) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode m : spec.path("modules_in_scope")) out.add(m.asText());
        for (JsonNode m : spec.path("modules")) out.add(m.path("api_name").asText());
        for (String s : new String[]{"fields", "layouts"}) {
            for (JsonNode f : spec.path(s)) out.add(f.path("module").asText());
        }
        out.remove("");
        return out;
    }

    /** State for one apply run: created modules, profiles and roles looked up once. */
    private static final class Run {
        final CrmClient crm;
        final ImplOrg org;
        final boolean dryRun;
        final String v = System.getenv().getOrDefault("CRM_SETTINGS_API_VERSION", "v8");
        final Map<String, String> createdModules = new LinkedHashMap<>();   // low(label/api) -> api_name
        List<String> profileIds;
        Map<String, String> roleIds;

        Run(CrmClient crm, ImplOrg org, boolean dryRun) { this.crm = crm; this.org = org; this.dryRun = dryRun; }

        void apply(String section, JsonNode item, ObjectNode r) throws Exception {
            switch (section) {
                case "modules": module(item, r); break;
                case "fields":  field(item, r); break;
                case "roles":   role(item, r); break;
                default: throw new IllegalStateException("No API executor for " + section);
            }
        }

        void module(JsonNode m, ObjectNode r) throws Exception {
            if (org != null && org.hasModule(m)) { r.put("result", "skipped"); r.put("detail", "Already in the org."); return; }
            ObjectNode mod = MAPPER.createObjectNode();
            mod.put("singular_label", m.path("singular_label").asText());
            mod.put("plural_label", m.path("plural_label").asText());
            mod.put("api_name", m.path("api_name").asText());
            ArrayNode profs = mod.putArray("profiles");
            for (String pid : profiles()) profs.addObject().put("id", pid);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.putArray("modules").add(mod);
            String api = send(r, "POST", "/crm/" + v + "/settings/modules", payload, "modules");
            String name = api == null || api.isEmpty() ? m.path("api_name").asText() : api;
            for (String k : new String[]{"api_name", "plural_label", "singular_label"}) {
                createdModules.put(ImplSpec.low(m.path(k).asText()), name);
            }
            if (!dryRun) r.put("api_name", name);
        }

        void field(JsonNode f, ObjectNode r) throws Exception {
            String module = moduleApi(f.path("module").asText());
            JsonNode live = org == null ? null : org.findField(f);
            if (live != null) {
                String why = ImplCompare.conflict(f, live);
                if (why != null) {
                    r.put("result", "conflict");
                    r.put("detail", why + " CRM can't change a field's type: create a new field instead, or skip.");
                    return;
                }
                List<String> missing = ImplCompare.missingPicklistValues(f, live);
                if (missing.isEmpty()) { r.put("result", "skipped"); r.put("detail", "Already in the org."); return; }
                addPicklistValues(module, live, missing, r);
                return;
            }
            String type = f.path("data_type").asText("text");
            ObjectNode fld = MAPPER.createObjectNode();
            fld.put("field_label", f.path("label").asText());
            fld.put("data_type", "decimal".equals(type) ? "double" : type);
            if (f.path("length").asInt(0) > 0) fld.put("length", f.path("length").asInt());
            else if ("text".equals(type)) fld.put("length", 255);
            if (type.contains("picklist")) {
                if (f.path("picklist_values").size() == 0) {
                    throw new IllegalStateException("Picklist has no values. Add them in review, then apply again.");
                }
                ArrayNode pv = fld.putArray("pick_list_values");
                for (JsonNode val : f.path("picklist_values")) {
                    pv.addObject().put("display_value", val.asText()).put("actual_value", val.asText());
                }
            }
            if ("lookup".equals(type)) {
                String target = f.path("lookup_module").asText("");
                if (target.isEmpty()) throw new IllegalStateException("Lookup target module isn't set.");
                ObjectNode lk = fld.putObject("lookup");
                lk.putObject("module").put("api_name", moduleApi(target));
                lk.put("display_label", f.path("module").asText());
            }
            if (f.path("unique").asBoolean(false)) fld.putObject("unique").put("casesensitive", "false");
            if (!f.path("tooltip").asText("").isEmpty()) {
                fld.putObject("tooltip").put("name", "static_text").put("value", f.path("tooltip").asText());
            }
            ArrayNode profs = fld.putArray("profiles");
            for (String pid : profiles()) profs.addObject().put("id", pid).put("permission_type", "read_write");
            ObjectNode payload = MAPPER.createObjectNode();
            payload.putArray("fields").add(fld);
            send(r, "POST", "/crm/" + v + "/settings/fields?module=" + enc(module), payload, "fields");
            if (f.path("required").asBoolean(false)) {
                r.put("follow_up", "Mark as mandatory in the " + f.path("module").asText()
                        + " layout (layout-level setting, not set by the field API).");
            }
        }

        /** PATCH an existing picklist: its current values (by id) plus the missing ones. */
        void addPicklistValues(String module, JsonNode live, List<String> missing, ObjectNode r) throws Exception {
            ObjectNode fld = MAPPER.createObjectNode();
            fld.put("id", live.path("id").asText());
            ArrayNode pv = fld.putArray("pick_list_values");
            for (JsonNode p : live.path("pick_list_values")) {
                if (p.hasNonNull("id")) pv.addObject().put("id", p.path("id").asText());
            }
            for (String v : missing) pv.addObject().put("display_value", v).put("actual_value", v);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.putArray("fields").add(fld);
            send(r, "PATCH", "/crm/" + v + "/settings/fields/" + live.path("id").asText() + "?module=" + enc(module),
                    payload, "fields");
            r.put("detail", (dryRun ? "Would add " : "Added ") + String.join(", ", missing) + " to "
                    + live.path("api_name").asText() + ".");
            if (dryRun) r.put("result", "would_update");
            else if ("created".equals(r.path("result").asText())) r.put("result", "updated");
        }

        void role(JsonNode role, ObjectNode r) throws Exception {
            if (org != null && org.hasRole(role.path("name").asText())) {
                r.put("result", "skipped"); r.put("detail", "Already in the org."); return;
            }
            Map<String, String> ids = roles();
            String parentName = role.path("reports_to").asText("");
            String parent = ids.get(ImplSpec.low(parentName));
            if (parent == null) {
                parent = ids.get("__top__");
                if (!parentName.isEmpty()) r.put("follow_up", "Reports-to role \"" + parentName
                        + "\" wasn't found; placed under the top role. Move it if needed.");
            }
            ObjectNode ro = MAPPER.createObjectNode();
            ro.put("name", role.path("name").asText());
            if (parent != null) ro.putObject("reporting_to").put("id", parent);
            ro.put("share_with_peers", role.path("share_with_peers").asBoolean(false));
            if (!role.path("description").asText("").isEmpty()) ro.put("description", role.path("description").asText());
            ObjectNode payload = MAPPER.createObjectNode();
            payload.putArray("roles").add(ro);
            String newId = send(r, "POST", "/crm/" + v + "/settings/roles", payload, "roles");
            if (!dryRun && newId != null) ids.put(ImplSpec.low(role.path("name").asText()), newId);
        }

        /** Sends (or previews) a settings create call; returns the created item's id/api_name when known. */
        String send(ObjectNode r, String method, String path, ObjectNode payload, String key) throws Exception {
            ObjectNode preview = r.putObject("request");
            preview.put("method", method);
            preview.put("path", path);
            preview.set("body", payload);
            if (dryRun) {
                r.put("result", "would_create");
                return null;
            }
            CrmClient.Response res = crm.call(method, path, MAPPER.writeValueAsString(payload));
            JsonNode first = res.json == null ? null : res.json.path(key).path(0);
            String code = first == null ? "" : first.path("code").asText("");
            if (res.statusCode >= 400 || (!code.isEmpty() && !"SUCCESS".equalsIgnoreCase(code))) {
                String msg = first != null && first.has("message") ? first.path("message").asText() + " "
                        + first.path("details").toString() : res.rawBody;
                throw new IllegalStateException("HTTP " + res.statusCode + ": " + ImplementServlet.trim(msg, 300));
            }
            r.put("result", "created");
            JsonNode details = first == null ? null : first.path("details");
            if (details != null && details.has("api_name")) return details.path("api_name").asText();
            if (details != null && details.has("id")) return details.path("id").asText();
            return "";
        }

        String moduleApi(String nameOrLabel) {
            String c = createdModules.get(ImplSpec.low(nameOrLabel));
            if (c != null) return c;
            String live = org == null ? null : org.moduleApiName(nameOrLabel);
            return live != null ? live : nameOrLabel;
        }

        List<String> profiles() throws Exception {
            if (profileIds != null) return profileIds;
            profileIds = new ArrayList<>();
            if (crm == null) return profileIds;
            CrmClient.Response res = crm.call("GET", "/crm/v3/settings/profiles", null);
            if (res.json != null) for (JsonNode p : res.json.path("profiles")) profileIds.add(p.path("id").asText());
            if (profileIds.isEmpty() && !dryRun) {
                throw new IllegalStateException("Couldn't read profiles (HTTP " + res.statusCode + ").");
            }
            return profileIds;
        }

        Map<String, String> roles() throws Exception {
            if (roleIds != null) return roleIds;
            roleIds = new LinkedHashMap<>();
            if (crm == null) return roleIds;
            CrmClient.Response res = crm.call("GET", "/crm/v3/settings/roles", null);
            if (res.json != null) {
                for (JsonNode ro : res.json.path("roles")) {
                    String id = ro.path("id").asText();
                    roleIds.put(ImplSpec.low(ro.path("name").asText(ro.path("display_label").asText())), id);
                    if (ro.path("reporting_to").isNull() || ro.path("reporting_to").isMissingNode()) {
                        roleIds.putIfAbsent("__top__", id);
                    }
                }
            }
            return roleIds;
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }
}
