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
            Products.use(body);
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
            ImplOrg org = crm == null ? null : Products.current().readOrg(crm, only.isEmpty() ? modulesOf(spec) : modulesOf(spec, only), false);
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
                            + (org.orgType.isEmpty() ? "unknown" : org.orgType) + "). LiftOff only builds in sandbox orgs, "
                            + "or in test orgs listed in IMPL_BUILD_ORG_IDS on uat_generator (this org: id " + org.orgId
                            + (org.zgid.isEmpty() ? "" : ", zgid " + org.zgid) + "), or anywhere when IMPL_ALLOW_PRODUCTION=1; "
                            + "production is updated by promoting a verified build. Nothing was changed.");
                    return;
                }
            }

            Run run = new Run(crm, org, dryRun);
            ArrayNode results = MAPPER.createArrayNode();
            ArrayNode rpaQueue = MAPPER.createArrayNode();

            for (String section : new String[]{"modules", "global_picklists", "fields", "roles", "layouts", "dependencies", "validation_rules",
                                               "workflows", "functions", "sharing"}) {
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
                            r.set("failure", ImplDiagnose.describe(e, section, item));
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
                        q.set("recipe", ImplRpa.recipe(section, item));
                        q.set("item", item);
                        r.put("detail", "No reliable create API; " + steps.size() + " UI step(s) queued"
                                + (q.path("recipe").size() > 0 ? " — the runner can do them itself." : "."));
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
                o.put("allow_listed", org.allowListed);
                o.put("production_allowed", org.productionAllowed);
                o.put("zgid", org.zgid);
                o.put("domain", org.domain);
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
        for (String section : new String[]{"modules", "global_picklists", "fields", "layouts", "dependencies", "validation_rules", "workflows", "functions", "sharing"}) {
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
                case "workflows":
                    if ("assignment".equals(item.path("type").asText())) assignment(item, r);
                    else if ("blueprint".equals(item.path("type").asText())) blueprint(item, r);
                    else if ("pipeline".equals(item.path("type").asText())) pipeline(item, r);
                    else workflow(item, r);
                    break;
                case "layouts": layout(item, r); break;
                case "dependencies": dependency(item, r); break;
                case "global_picklists": globalPicklist(item, r); break;
                case "sharing": {
                    String kind = item.path("type").asText();
                    if ("territory".equals(kind)) territory(item, r);
                    else if ("group".equals(kind)) userGroup(item, r);
                    else sharingRule(item, r);
                    break;
                }
                case "functions": function(item, r); break;
                default: throw new IllegalStateException("No API executor for " + section);
            }
        }

        void module(JsonNode m, ObjectNode r) throws Exception {
            if (org != null && org.hasModule(m)) { r.put("result", "skipped"); r.put("detail", "Already in the org."); return; }
            ObjectNode mod = MAPPER.createObjectNode();
            mod.put("singular_label", moduleLabel(m.path("singular_label").asText()));
            mod.put("plural_label", moduleLabel(m.path("plural_label").asText()));
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
            int len = f.path("length").asInt(0);
            if (len > 0) fld.put("length", len);
            else if ("text".equals(type)) fld.put("length", 255);
            else if ("decimal".equals(type)) fld.put("length", 16);
            if ("textarea".equals(type)) {
                fld.putObject("textarea").put("type", len > 2000 ? "large" : "small");
            }
            if ("currency".equals(type)) fld.putObject("currency").put("rounding_option", "normal");
            String gplName = f.path("global_picklist").asText("");
            if (type.contains("picklist") && !gplName.isEmpty()) {
                // Shared option set: the field carries global_picklist:{id} and no options of its own.
                String gplId = dryRun && crm == null ? null : globalSets().get(ImplSpec.low(gplName));
                if (gplId == null) {
                    if (!dryRun) throw new IllegalStateException("Global picklist \"" + gplName + "\" isn't in the org; it is created before the fields that use it.");
                    gplId = "<id of global picklist " + gplName + ">";
                }
                fld.putObject("global_picklist").put("id", gplId);
                r.put("global_picklist", gplName);
            } else if (type.contains("picklist")) {
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
                lk.put("module", moduleApi(target));          // a module api name string, not an object
                lk.put("display_label", f.path("module").asText());
            }
            if (f.path("unique").asBoolean(false)) fld.putObject("unique").put("case_sensitive", false);
            String tip = f.path("tooltip").asText("");
            if (!tip.isEmpty()) {
                // Picklists, dates, booleans and uploads accept only info_icon; static_text is max 35 chars.
                boolean iconOnly = type.contains("picklist") || type.startsWith("date") || "boolean".equals(type)
                        || tip.length() > 35;
                fld.putObject("tooltip").put("name", iconOnly ? "info_icon" : "static_text").put("value", tip);
            }
            ArrayNode profs = fld.putArray("profiles");
            for (String pid : profiles()) profs.addObject().put("id", pid).put("permission_type", "read_write");
            ObjectNode payload = MAPPER.createObjectNode();
            payload.putArray("fields").add(fld);
            String newId = send(r, "POST", "/crm/" + v + "/settings/fields?module=" + enc(module), payload, "fields");
            if (f.path("required").asBoolean(false)) {
                // Mandatory is a layout setting: mark it through the layouts API.
                if (dryRun) {
                    r.put("follow_up", "Then marks it mandatory: PATCH /crm/" + v + "/settings/layouts/{layout}?module=" + module + ".");
                } else {
                    try {
                        markRequired(module, newId);
                        r.put("follow_up", "Marked mandatory in the layout.");
                    } catch (Exception e) {
                        r.put("follow_up", "Created, but couldn't mark it mandatory (" + ImplementServlet.trim(e.getMessage(), 200)
                                + "). Mark it as required in the " + f.path("module").asText() + " layout.");
                    }
                }
            }
        }

        /** PATCH the layout section(s) holding the field: {"layouts":[{"sections":[{"id","fields":[{"id","required":true}]}]}]} */
        void markRequired(String module, String fieldId) throws Exception {
            if (fieldId == null || fieldId.isEmpty()) throw new IllegalStateException("no field id returned");
            CrmClient.Response res = crm.call("GET", "/crm/" + v + "/settings/layouts?module=" + enc(module), null);
            if (res.json == null) throw new IllegalStateException("couldn't read layouts (HTTP " + res.statusCode + ")");
            int marked = 0;
            for (JsonNode layout : res.json.path("layouts")) {
                for (JsonNode sec : layout.path("sections")) {
                    for (JsonNode fl : sec.path("fields")) {
                        if (!fieldId.equals(fl.path("id").asText())) continue;
                        ObjectNode body = MAPPER.createObjectNode();
                        ObjectNode so = body.putArray("layouts").addObject().putArray("sections").addObject();
                        so.put("id", sec.path("id").asText());
                        so.putArray("fields").addObject().put("id", fieldId).put("required", true);
                        CrmClient.Response pr = crm.call("PATCH", "/crm/" + v + "/settings/layouts/" + layout.path("id").asText()
                                + "?module=" + enc(module), MAPPER.writeValueAsString(body));
                        JsonNode first = pr.json == null ? null : pr.json.path("layouts").path(0);
                        String code = first == null ? "" : first.path("code").asText("");
                        if (pr.statusCode >= 400 || (!code.isEmpty() && !"SUCCESS".equalsIgnoreCase(code))) {
                            throw new IllegalStateException("HTTP " + pr.statusCode + ": " + ImplementServlet.trim(pr.rawBody, 200));
                        }
                        marked++;
                    }
                }
            }
            if (marked == 0) throw new IllegalStateException("the new field isn't on any layout yet");
        }

        /** Builds a workflow rule: its field-update and task actions first, then the rule (ImplRules). */
        void workflow(JsonNode wf, ObjectNode r) throws Exception {
            String moduleName = wf.path("module").asText();
            String module = moduleApi(moduleName);
            if (org != null && Boolean.TRUE.equals(org.hasWorkflow(module, wf.path("name").asText()))) {
                r.put("result", "skipped"); r.put("detail", "A rule with this name already exists."); return;
            }
            String moduleId = org == null ? "" : org.moduleId(module);
            if (moduleId.isEmpty() && !dryRun) throw new IllegalStateException("Module " + module + " isn't in the org.");
            String rule = ImplRules.ruleName(wf.path("name").asText());
            ArrayNode requests = r.putArray("requests");
            java.util.function.Function<String, JsonNode> fieldOf = (api) -> {
                JsonNode live = org == null ? null : org.field(module, api);
                if (live != null || !dryRun) return live;
                // Dry run: a field built earlier in this run doesn't exist yet; show a placeholder id.
                return MAPPER.createObjectNode().put("api_name", api).put("id", "<id of " + api + ">").put("field_label", api);
            };

            List<String[]> actions = new ArrayList<>();
            for (JsonNode fu : wf.path("field_updates")) {
                JsonNode live = fieldOf.apply(fu.path("field").asText());
                if (live == null) throw new IllegalStateException("Field " + fu.path("field").asText() + " isn't in " + module + " yet.");
                ensurePicklistValue(module, live, fu.path("value"), requests);
                ObjectNode body = ImplRules.fieldUpdate(module, moduleId, rule, live, fu.path("value"));
                actions.add(new String[]{"field_updates", call(requests, "POST", "/crm/" + v + "/settings/automation/field_updates",
                        body, "field_updates", "field update")});
            }
            for (JsonNode t : wf.path("tasks")) {
                ObjectNode body = ImplRules.task(module, moduleId, t, this::taskField);
                actions.add(new String[]{"tasks", call(requests, "POST", "/crm/" + v + "/settings/automation/tasks",
                        body, "tasks", "task")});
            }
            for (JsonNode h : wf.path("webhooks")) {
                ObjectNode body = ImplRules.webhook(module, moduleId, rule, h);
                actions.add(new String[]{"webhooks", call(requests, "POST", "/crm/" + v + "/settings/automation/webhooks",
                        body, "webhooks", "webhook")});
            }
            List<String> problems = new ArrayList<>();
            emailAlerts(wf.path("email_alerts"), module, moduleId, rule, "", fieldOf, requests, actions, problems);
            List<ImplRules.Scheduled> scheduled = new ArrayList<>();
            int delayed = 0;
            for (JsonNode sa : wf.path("scheduled_actions")) {
                List<String[]> sacts = new ArrayList<>();
                for (JsonNode fu : sa.path("field_updates")) {
                    JsonNode live = fieldOf.apply(fu.path("field").asText());
                    if (live == null) throw new IllegalStateException("Field " + fu.path("field").asText() + " isn't in " + module + " yet.");
                    ensurePicklistValue(module, live, fu.path("value"), requests);
                    sacts.add(new String[]{"field_updates", call(requests, "POST", "/crm/" + v + "/settings/automation/field_updates",
                            ImplRules.fieldUpdate(module, moduleId, rule + " (delayed)", live, fu.path("value")), "field_updates", "delayed field update")});
                }
                for (JsonNode t : sa.path("tasks")) {
                    sacts.add(new String[]{"tasks", call(requests, "POST", "/crm/" + v + "/settings/automation/tasks",
                            ImplRules.task(module, moduleId, t, this::taskField), "tasks", "delayed task")});
                }
                for (JsonNode h : sa.path("webhooks")) {
                    sacts.add(new String[]{"webhooks", call(requests, "POST", "/crm/" + v + "/settings/automation/webhooks",
                            ImplRules.webhook(module, moduleId, rule + " (delayed)", h), "webhooks", "delayed webhook")});
                }
                emailAlerts(sa.path("email_alerts"), module, moduleId, rule, " delayed", fieldOf, requests, sacts, problems);
                if (!sacts.isEmpty()) { scheduled.add(new ImplRules.Scheduled(sa.path("after").path("unit").asInt(1), sa.path("after").path("period").asText("hours"), sacts)); delayed += sacts.size(); }
            }
            ObjectNode body = ImplRules.rule(wf, module, moduleId, fieldOf, actions, scheduled, org == null ? "+00:00" : org.utcOffset());
            call(requests, "POST", "/crm/" + v + "/settings/automation/workflow_rules", body, "workflow_rules", "rule");
            r.put("result", dryRun ? "would_create" : "created");
            r.put("detail", (dryRun ? "Would create " : "Created ") + actions.size() + " action(s)" + (delayed > 0 ? ", " + delayed + " delayed action(s)" : "") + " and the rule \"" + rule + "\".");
            List<String> follow = new ArrayList<>(problems);
            if (wf.path("other_actions").size() > 0) follow.add("Add by hand: " + ImplPlan.join(wf.path("other_actions")) + ".");
            if (!follow.isEmpty()) r.put("follow_up", String.join(" ", follow));
        }

        /** Assignment rule through POST settings/automation/assignment_rules?module=X (users resolved by email or name). */
        void assignment(JsonNode wf, ObjectNode r) throws Exception {
            String module = moduleApi(wf.path("module").asText());
            java.util.function.Function<String, JsonNode> fieldOf = (api) -> {
                JsonNode live = org == null ? null : org.field(module, api);
                if (live != null || !dryRun) return live;
                return MAPPER.createObjectNode().put("api_name", api).put("id", "<id of " + api + ">").put("field_label", api);
            };
            ObjectNode body = ImplRules.assignmentRule(wf, module, fieldOf, this::userId);
            send(r, "POST", "/crm/" + v + "/settings/automation/assignment_rules?module=" + enc(module), body, "assignment_rules");
            r.put("follow_up", "Assignment rules run for records that arrive through web forms, imports and the API "
                    + "(pass the rule's id as lar_id); records typed in by hand keep their owner.");
        }

        Map<String, String> users;
        /** Email or full name (case-insensitive) → active user id; null when unknown. Dry run returns a placeholder. */
        String userId(String who) {
            if (who == null || who.trim().isEmpty()) return null;
            if (users == null) {
                users = new LinkedHashMap<>();
                try {
                    CrmClient.Response res = crm == null ? null : crm.call("GET", "/crm/v3/users?type=ActiveUsers", null);
                    if (res != null && res.json != null) for (JsonNode u : res.json.path("users")) {
                        String id = u.path("id").asText("");
                        users.put(ImplSpec.low(u.path("email").asText("")), id);
                        users.put(ImplSpec.low(u.path("full_name").asText("")), id);
                    }
                } catch (Exception ignored) { /* reported as an unknown user */ }
            }
            String id = users.get(ImplSpec.low(who));
            if (id == null && dryRun) return "<id of user " + who + ">";
            return id;
        }

        /**
         * Places fields into sections of the module's layout: PATCH settings/layouts/{id}?module=X with
         * {"layouts":[{"sections":[{"id"|"display_label", "fields":[{"id"}]}]}]}. An unknown section is created.
         */
        void layout(JsonNode lo, ObjectNode r) throws Exception {
            String module = moduleApi(lo.path("module").asText());
            String want = lo.path("name").asText("Standard");
            JsonNode layouts = crm == null ? null : crm.call("GET", "/crm/" + v + "/settings/layouts?module=" + enc(module), null).json;
            JsonNode layout = null;
            if (layouts != null) {
                for (JsonNode l : layouts.path("layouts")) if (l.path("name").asText("").equalsIgnoreCase(want)) layout = l;
                if (layout == null) for (JsonNode l : layouts.path("layouts")) if (layout == null || l.path("name").asText("").equalsIgnoreCase("Standard")) layout = l;
            }
            if (layout == null && !dryRun) throw new IllegalStateException("Couldn't read the layouts of " + module + ".");
            String layoutId = layout == null ? "<layout id>" : layout.path("id").asText();
            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode secs = body.putArray("layouts").addObject().putArray("sections");
            int placed = 0; List<String> missing = new ArrayList<>(), created = new ArrayList<>();
            for (JsonNode sct : lo.path("sections")) {
                String name = sct.path("name").asText("");
                JsonNode live = null;
                if (layout != null) for (JsonNode ls : layout.path("sections")) if (ls.path("display_label").asText("").equalsIgnoreCase(name)) live = ls;
                ObjectNode so = secs.addObject();
                if (live != null) so.put("id", live.path("id").asText()); else { so.put("display_label", name); created.add(name); }
                ArrayNode fs = so.putArray("fields");
                for (JsonNode fl : sct.path("fields")) {
                    String label = fl.asText("");
                    JsonNode f = org == null ? null : org.field(module, label);
                    if (f == null) { if (dryRun) fs.addObject().put("id", "<id of " + label + ">"); else missing.add(label); continue; }
                    boolean already = live != null && hasField(live, f.path("id").asText());
                    if (already) continue;
                    fs.addObject().put("id", f.path("id").asText());
                    placed++;
                }
                if (fs.size() == 0 && live != null) secs.remove(secs.size() - 1);
            }
            if (!missing.isEmpty()) throw new IllegalStateException("Field(s) not in " + module + " yet: " + String.join(", ", missing) + " — build them first.");
            if (secs.size() == 0) { r.put("result", "skipped"); r.put("detail", "Every field is already in its section."); return; }
            send(r, "PATCH", "/crm/" + v + "/settings/layouts/" + layoutId + "?module=" + enc(module), body, "layouts");
            if (!dryRun) r.put("result", "updated");
            r.put("detail", (dryRun ? "Would place " : "Placed ") + placed + " field(s) in layout \"" + want + "\""
                    + (created.isEmpty() ? "" : ", creating section(s) " + String.join(", ", created)) + ".");
            if (lo.path("profiles").size() > 0) r.put("follow_up", "Assign the layout to profiles " + ImplPlan.join(lo.path("profiles")) + " in Setup.");
        }

        private static boolean hasField(JsonNode section, String fieldId) {
            for (JsonNode f : section.path("fields")) if (fieldId.equals(f.path("id").asText())) return true;
            return false;
        }

        /** One call of a multi-call build; records the request, returns the new id (a placeholder on a dry run). */
        String call(ArrayNode requests, String method, String path, ObjectNode payload, String key, String what) throws Exception {
            ObjectNode rq = requests.addObject();
            rq.put("method", method);
            rq.put("path", path);
            rq.set("body", payload);
            if (dryRun) return "<new " + what + " id>";
            CrmClient.Response res = crm.call(method, path, MAPPER.writeValueAsString(payload));
            JsonNode first = res.json == null ? null : res.json.path(key).path(0);
            String code = first == null ? "" : first.path("code").asText("");
            rq.put("status", res.statusCode);
            if (res.statusCode >= 400 || (!code.isEmpty() && !"SUCCESS".equalsIgnoreCase(code))) {
                String msg = first != null && first.has("message") ? first.path("message").asText() + " " + first.path("details") : res.rawBody;
                if (res.json != null) rq.set("response", res.json); else rq.put("response", ImplementServlet.trim(res.rawBody, 2000));
                throw new ImplDiagnose.StepFailure(what + ": HTTP " + res.statusCode + ": " + ImplementServlet.trim(msg, 300),
                        method, path, payload, res.statusCode, res.rawBody);
            }
            String id = first == null ? "" : first.path("details").path("id").asText("");
            rq.put("created_id", id);
            return id;
        }

        Map<String, JsonNode> templatesByModule = new LinkedHashMap<>();
        JsonNode templates(String module) throws Exception {
            if (templatesByModule.containsKey(module)) return templatesByModule.get(module);
            JsonNode t = crm == null ? null : crm.call("GET", "/crm/" + v + "/settings/email_templates?module=" + enc(module), null).json;
            templatesByModule.put(module, t);
            return t;
        }

        /**
         * Email alerts of a rule (or of one delayed block): one email_notifications action per alert, with an
         * existing template and recipients resolved from words. Alerts that can't be built are appended to
         * {@code problems} and the rule is still created without them.
         */
        void emailAlerts(JsonNode alerts, String module, String moduleId, String rule, String suffix,
                         java.util.function.Function<String, JsonNode> fieldOf, ArrayNode requests, List<String[]> actions, List<String> problems) throws Exception {
            int n = 0;
            for (JsonNode al : alerts) {
                n++;
                String tname = al.path("template").asText("");
                JsonNode tpl = dryRun && crm == null ? null : ImplEmailAlerts.template(templates(module), tname);
                if (tpl == null) {
                    if (dryRun) tpl = MAPPER.createObjectNode().put("id", "<id of template " + tname + ">").put("name", tname);
                    else { problems.add("email template \"" + tname + "\" not found on " + module + " — create it in Setup → Customization → Templates → Email, then add the alert to the rule"); continue; }
                }
                ArrayNode to = MAPPER.createArrayNode();
                List<String> unmatched = new ArrayList<>();
                Map<String, String> roleIds = dryRun && crm == null ? new LinkedHashMap<>() : roles();
                for (JsonNode who : al.path("to")) {
                    ObjectNode rcp = ImplEmailAlerts.recipient(who.asText(""), module, roleIds, fieldOf);
                    if (rcp == null) unmatched.add(who.asText("")); else to.add(rcp);
                }
                if (to.size() == 0) { problems.add("email alert \"" + tname + "\": no recipient recognised in " + unmatched); continue; }
                if (!unmatched.isEmpty()) problems.add("email alert \"" + tname + "\": recipient(s) not recognised: " + String.join(", ", unmatched) + " — add them to the alert in Setup");
                ObjectNode body = ImplEmailAlerts.body(rule + suffix + " - email " + n, module, moduleId, tpl, to);
                actions.add(new String[]{"email_notifications", call(requests, "POST", "/crm/" + v + "/settings/automation/email_notifications", body, "email_notifications", "email alert")});
            }
        }

        Map<String, JsonNode> taskFields;
        JsonNode taskField(String api) {
            if (taskFields == null) {
                taskFields = new LinkedHashMap<>();
                try {
                    CrmClient.Response res = crm == null ? null : crm.call("GET", "/crm/v3/settings/fields?module=Tasks", null);
                    if (res != null && res.json != null) for (JsonNode f : res.json.path("fields")) taskFields.put(f.path("api_name").asText(), f);
                } catch (Exception ignored) { /* reported as a missing field below */ }
            }
            JsonNode f = taskFields.get(api);
            if (f == null && dryRun) return MAPPER.createObjectNode().put("api_name", api).put("id", "<id of Tasks." + api + ">");
            return f;
        }

        /** PATCH an existing picklist with only the new options (existing ones are kept). */
        void addPicklistValues(String module, JsonNode live, List<String> missing, ObjectNode r) throws Exception {
            ObjectNode fld = MAPPER.createObjectNode();
            ArrayNode pv = fld.putArray("pick_list_values");
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

        /** A field update that writes a picklist value CRM doesn't have yet is rejected (INVALID_DATA on
         *  $.field_updates[0].value), so the option is added first — one PATCH, existing options kept. */
        void ensurePicklistValue(String module, JsonNode live, JsonNode value, ArrayNode requests) throws Exception {
            String type = live.path("data_type").asText("");
            if (!type.contains("picklist") || value == null || value.isNull() || !live.has("pick_list_values")) return;
            String want = value.asText().trim();
            if (want.isEmpty()) return;
            for (JsonNode pv : live.path("pick_list_values")) {
                if (want.equalsIgnoreCase(pv.path("actual_value").asText()) || want.equalsIgnoreCase(pv.path("display_value").asText())) return;
            }
            ObjectNode fld = MAPPER.createObjectNode();
            fld.putArray("pick_list_values").addObject().put("display_value", want).put("actual_value", want);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.putArray("fields").add(fld);
            call(requests, "PATCH", "/crm/" + v + "/settings/fields/" + live.path("id").asText() + "?module=" + enc(module),
                    payload, "fields", "picklist option \"" + want + "\"");
            if (!dryRun && live instanceof ObjectNode) {
                ((ObjectNode) live).withArray("pick_list_values").addObject().put("display_value", want).put("actual_value", want);
            }
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
            if (role.path("permissions").size() > 0) profile(role, r);
        }

        Map<String, String> profileByName;
        Map<String, String> profileNames() throws Exception {
            if (profileByName != null) return profileByName;
            profileByName = new LinkedHashMap<>();
            if (crm == null) return profileByName;
            CrmClient.Response res = crm.call("GET", "/crm/" + v + "/settings/profiles", null);
            if (res.json != null) for (JsonNode p : res.json.path("profiles")) profileByName.put(ImplSpec.low(p.path("name").asText(p.path("display_label").asText())), p.path("id").asText());
            return profileByName;
        }

        /**
         * Profile for a role whose permissions are listed: clone the base profile (the spec's "profile" when it
         * exists in the org, else Standard) under the role's profile name, then PUT the permissions parsed from
         * the spec's words. Permissions that match nothing are reported as a follow-up.
         */
        void profile(JsonNode role, ObjectNode r) throws Exception {
            ArrayNode requests = r.withArray("requests");
            Map<String, String> names = profileNames();
            String wanted = role.path("profile").asText("").trim();
            String baseName = names.containsKey(ImplSpec.low(wanted)) ? wanted : "Standard";
            String baseId = names.get(ImplSpec.low(baseName));
            if (baseId == null) baseId = names.values().stream().findFirst().orElse(null);
            String newName = wanted.isEmpty() || names.containsKey(ImplSpec.low(wanted)) ? role.path("name").asText() + " Profile" : wanted;
            if (newName.length() > 50) newName = newName.substring(0, 50);
            r.put("profile", newName);
            String profileId;
            if (names.containsKey(ImplSpec.low(newName))) {
                profileId = names.get(ImplSpec.low(newName));
                requests.addObject().put("note", "Profile \"" + newName + "\" already exists; permissions updated on it.");
            } else {
                if (baseId == null && !dryRun) throw new IllegalStateException("Couldn't read the org's profiles to clone one.");
                profileId = call(requests, "POST", "/crm/" + v + "/settings/profiles/" + (baseId == null ? "<Standard profile id>" : baseId) + "/actions/clone",
                        ImplProfiles.cloneBody(newName, role.path("description").asText("")), "profiles", "profile");
                if (!dryRun) names.put(ImplSpec.low(newName), profileId);
            }
            List<ImplProfiles.Want> wants = ImplProfiles.parse(role.path("permissions"));
            if (dryRun) {
                ObjectNode preview = requests.addObject(); preview.put("method", "PUT"); preview.put("path", "/crm/" + v + "/settings/profiles/" + profileId);
                ArrayNode asked = preview.putArray("permissions");
                for (ImplProfiles.Want w : wants) asked.add((w.enabled ? "enable " : "disable ") + (w.action.isEmpty() ? "" : w.action + " ") + w.module);
                r.put("detail", "Would create the role, clone \"" + baseName + "\" as \"" + newName + "\" and set " + wants.size() + " permission(s).");
                return;
            }
            JsonNode detail = crm.call("GET", "/crm/" + v + "/settings/profiles/" + profileId, null).json;
            JsonNode pds = detail == null ? MAPPER.createArrayNode() : detail.path("profiles").path(0).path("permissions_details");
            ObjectNode matched = ImplProfiles.match(wants, pds, org);
            if (matched.path("changes").size() > 0) {
                call(requests, "PUT", "/crm/" + v + "/settings/profiles/" + profileId, ImplProfiles.updateBody(matched.withArray("changes")), "profiles", "profile permissions");
            }
            r.set("permissions_set", matched.path("changes"));
            r.put("detail", "Created the role and profile \"" + newName + "\" (from " + baseName + "); " + matched.path("changes").size() + " permission(s) set.");
            if (matched.path("unmatched").size() > 0) {
                r.put("follow_up", "No profile permission matched: " + ImplPlan.join(matched.path("unmatched")) + " — set these in Setup → Security Control → Profiles → " + newName + ".");
            }
            if (org != null && org.roles != null) r.put("note", "Assign the profile to the role's users (profiles are per user).");
        }

        Map<String, String> groupIds;
        Map<String, String> groups() throws Exception {
            if (groupIds != null) return groupIds;
            groupIds = new LinkedHashMap<>();
            if (crm == null) return groupIds;
            try {
                CrmClient.Response res = crm.call("GET", "/crm/" + v + "/settings/user_groups", null);
                if (res.json != null) for (JsonNode g : res.json.path("user_groups")) groupIds.put(ImplSpec.low(g.path("name").asText()), g.path("id").asText());
            } catch (Exception ignored) { /* groups are optional */ }
            return groupIds;
        }

        /**
         * User group through POST settings/user_groups. Members are resolved from words: an email or
         * full name is a user, "X role [and subordinates]" a role, "X group" a group, "X territory" a
         * territory. Members that resolve to nothing are reported; the group is created with the rest.
         */
        void userGroup(JsonNode g, ObjectNode r) throws Exception {
            ArrayNode requests = r.putArray("requests");
            String name = g.path("name").asText();
            Map<String, String> have = dryRun && crm == null ? new LinkedHashMap<>() : groups();
            if (have.containsKey(ImplSpec.low(name))) { r.put("result", "skipped"); r.put("detail", "A user group named \"" + name + "\" already exists."); return; }
            List<ObjectNode> sources = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (JsonNode m : g.path("members")) {
                String text = m.asText("");
                String type = ImplSharing.memberType(text);
                String who = ImplSharing.memberName(text);
                boolean subs = ImplSharing.subordinates(text);
                String id = null;
                if ("users".equals(type)) id = userId(who.isEmpty() ? text : who);
                else if ("roles".equals(type)) id = (dryRun && crm == null ? new LinkedHashMap<String, String>() : roles()).get(ImplSpec.low(who));
                else if ("groups".equals(type)) id = have.get(ImplSpec.low(who));
                else if ("territories".equals(type)) id = (dryRun && crm == null ? new LinkedHashMap<String, String>() : territories()).get(ImplSpec.low(who));
                if (id == null && dryRun) id = "<id of " + type + " " + who + ">";
                if (id == null) { unknown.add(text); continue; }
                sources.add(ImplSharing.member(type, id, who.isEmpty() ? text : who, subs));
            }
            if (sources.isEmpty()) throw new IllegalStateException("None of the members matched a user, role, group or territory in the org: " + unknown);
            ObjectNode payload = ImplSharing.userGroup(g, sources);
            String id = call(requests, "POST", "/crm/" + v + "/settings/user_groups", payload, "user_groups", "user group");
            if (!dryRun && id != null && !id.isEmpty()) have.put(ImplSpec.low(name), id);
            r.put("result", dryRun ? "would_create" : "created");
            r.put("detail", (dryRun ? "Would create " : "Created ") + "user group \"" + name + "\" with " + sources.size() + " member source(s).");
            if (!unknown.isEmpty()) r.put("follow_up", "Not found in the org, so left out of the group: " + String.join(", ", unknown) + " — add them in Setup → Users and Control → Groups.");
        }

        Map<String, String> territoryIds;
        /** lower-cased territory name → id, plus "__top__" for the root territory. */
        Map<String, String> territories() throws Exception {
            if (territoryIds != null) return territoryIds;
            territoryIds = new LinkedHashMap<>();
            if (crm == null) return territoryIds;
            CrmClient.Response res = crm.call("GET", "/crm/" + v + "/settings/territories", null);
            if (res.statusCode == 403) throw new IllegalStateException("Territory Management isn't enabled in this org (or the edition doesn't include it). "
                    + "Enable it in Setup → Users and Control → Territory Management, then Retry this step.");
            if (res.json != null) for (JsonNode t : res.json.path("territories")) {
                territoryIds.put(ImplSpec.low(t.path("name").asText()), t.path("id").asText());
                if (t.path("reporting_to").isNull() || t.path("reporting_to").isMissingNode()) territoryIds.putIfAbsent("__top__", t.path("id").asText());
            }
            return territoryIds;
        }

        /** Territory through POST settings/territories, under the named parent (else the top territory). */
        void territory(JsonNode t, ObjectNode r) throws Exception {
            ArrayNode requests = r.putArray("requests");
            Map<String, String> have = dryRun && crm == null ? new LinkedHashMap<>() : territories();
            String name = t.path("name").asText();
            if (have.containsKey(ImplSpec.low(name))) { r.put("result", "skipped"); r.put("detail", "A territory named \"" + name + "\" already exists."); return; }
            String parentName = t.path("parent").asText("");
            String parentId = have.get(ImplSpec.low(parentName));
            if (parentId == null) {
                parentId = have.get("__top__");
                if (!parentName.isEmpty()) r.put("follow_up", "Parent territory \"" + parentName + "\" wasn't found; placed under the top territory.");
            }
            if (parentId == null) {
                if (!dryRun) throw new IllegalStateException("Couldn't read the org's territories to find the parent.");
                parentId = "<top territory id>";
            }
            String managerId = t.path("manager").asText("").isEmpty() ? null : userId(t.path("manager").asText());
            String module = t.path("criteria").size() > 0 ? t.path("criteria").get(0).path("module").asText("") : "";
            final String mod = module.isEmpty() ? "" : moduleApi(module);
            ObjectNode payload = ImplSharing.territory(t, parentId, managerId, (api) -> mod.isEmpty() ? null : (org == null ? null : org.field(mod, api)));
            String id = call(requests, "POST", "/crm/" + v + "/settings/territories", payload, "territories", "territory");
            if (!dryRun && id != null) have.put(ImplSpec.low(name), id);
            r.put("result", dryRun ? "would_create" : "created");
            r.put("detail", (dryRun ? "Would create " : "Created ") + "territory \"" + name + "\" (" + payload.path("territories").path(0).path("permission_type").asText()
                    + (t.path("criteria").size() > 0 ? ", " + t.path("criteria").size() + " assignment rule(s)" : "") + ").");
        }

        /** Data sharing rule through POST settings/data_sharing/rules?module=X. */
        void sharingRule(JsonNode sr, ObjectNode r) throws Exception {
            String module = moduleApi(sr.path("module").asText());
            ArrayNode requests = r.putArray("requests");
            if (crm != null && !dryRun) {
                JsonNode have = crm.call("GET", "/crm/" + v + "/settings/data_sharing/rules?module=" + enc(module), null).json;
                if (ImplSharing.existing(have, "sharing_rules", sr.path("name").asText()) != null) {
                    r.put("result", "skipped"); r.put("detail", "A sharing rule with this name already exists on " + module + "."); return;
                }
            }
            Map<String, String> roleIds = dryRun && crm == null ? new LinkedHashMap<>() : roles();
            Map<String, String> grp = dryRun && crm == null ? new LinkedHashMap<>() : groups();
            ObjectNode to = ImplSharing.audience(sr.path("shared_to").asText(""), roleIds, grp);
            if (to == null) throw new IllegalStateException("Couldn't match \"" + sr.path("shared_to").asText() + "\" to a role or group in the org — name the exact role or group.");
            ObjectNode from = null;
            ArrayNode group = MAPPER.createArrayNode();
            final String mod = module;
            java.util.function.Function<String, JsonNode> fieldOf = (api) -> org == null ? null : org.field(mod, api);
            for (JsonNode c : sr.path("conditions")) group.add(ImplSharing.condition(c, fieldOf));
            if (group.size() == 0) {
                from = ImplSharing.audience(sr.path("shared_from").asText(""), roleIds, grp);
                if (from == null) throw new IllegalStateException("An owner-based sharing rule needs shared_from (whose records are shared) as a role or group, "
                        + "or conditions for a criteria-based rule.");
                if ("all_users".equals(from.path("type").asText())) throw new IllegalStateException("shared_from must be a role or a group, not all users.");
            }
            ObjectNode payload = ImplSharing.sharingRule(sr, to, from, group);
            call(requests, "POST", "/crm/" + v + "/settings/data_sharing/rules?module=" + enc(module), payload, "sharing_rules", "sharing rule");
            r.put("result", dryRun ? "would_create" : "created");
            JsonNode made = payload.path("sharing_rules").path(0);
            r.put("detail", (dryRun ? "Would share " : "Shared ") + module + " records with " + sr.path("shared_to").asText() + " as "
                    + made.path("permission_type").asText() + " (" + made.path("type").asText() + ").");
        }

        Map<String, String> globalSets;      // lower-cased name/api_name → id (org's sets plus ones created here)
        Map<String, String> globalSets() throws Exception {
            if (globalSets != null) return globalSets;
            globalSets = new LinkedHashMap<>();
            if (crm == null) return globalSets;
            CrmClient.Response res = crm.call("GET", "/crm/" + v + "/settings/global_picklists", null);
            if (res.json != null) for (JsonNode g : res.json.path("global_picklists")) {
                String id = g.path("id").asText();
                for (String k : new String[]{"api_name", "display_label", "actual_label"}) {
                    String n = g.path(k).asText("");
                    if (!n.isEmpty()) globalSets.put(ImplSpec.low(n), id);
                }
            }
            return globalSets;
        }

        /** Global picklist (shared option set) through POST settings/global_picklists — one set per call. */
        void globalPicklist(JsonNode g, ObjectNode r) throws Exception {
            String name = g.path("name").asText();
            Map<String, String> have = dryRun && crm == null ? new LinkedHashMap<>() : globalSets();
            if (have.containsKey(ImplSpec.low(name))) {
                r.put("result", "skipped");
                r.put("detail", "A global picklist named \"" + name + "\" already exists; fields will use it.");
                return;
            }
            ObjectNode payload = ImplGlobalPicklists.payload(g);
            String id = send(r, "POST", "/crm/" + v + "/settings/global_picklists", payload, "global_picklists");
            if (!dryRun && id != null && !id.isEmpty()) have.put(ImplSpec.low(name), id);
            r.put("detail", (dryRun ? "Would create " : "Created ") + "the shared option set \"" + name + "\" with "
                    + payload.path("global_picklists").path(0).path("pick_list_values").size() + " option(s).");
        }

        /**
         * Picklist dependency through POST settings/layouts/{layout}/map_dependency: the parent and child
         * picklists by api name or label (fields created earlier in this run included), options the spec names
         * added to either picklist first, then the whole mapping in one call. A pair already mapped is skipped.
         */
        void dependency(JsonNode d, ObjectNode r) throws Exception {
            String module = moduleApi(d.path("module").asText());
            ArrayNode requests = r.putArray("requests");
            JsonNode layouts = crm == null ? null : crm.call("GET", "/crm/" + v + "/settings/layouts?module=" + enc(module), null).json;
            JsonNode layout = null;
            if (layouts != null) for (JsonNode l : layouts.path("layouts")) if (layout == null || l.path("name").asText("").equalsIgnoreCase("Standard")) layout = l;
            if (layout == null && !dryRun) throw new IllegalStateException("Couldn't read the layouts of " + module + ".");
            String layoutId = layout == null ? "<layout id>" : layout.path("id").asText();
            JsonNode parent = liveField(module, d.path("parent").asText()), child = liveField(module, d.path("child").asText());
            if (parent == null || child == null) {
                if (!dryRun) throw new IllegalStateException("Field " + (parent == null ? d.path("parent").asText() : d.path("child").asText()) + " isn't in " + module + " yet.");
                if (parent == null) parent = MAPPER.createObjectNode().put("api_name", d.path("parent").asText()).put("id", "<id>").put("data_type", "picklist");
                if (child == null) child = MAPPER.createObjectNode().put("api_name", d.path("child").asText()).put("id", "<id>").put("data_type", "picklist");
            }
            if (!parent.path("data_type").asText("").contains("picklist") || !child.path("data_type").asText("").contains("picklist")) {
                throw new IllegalStateException("A dependency needs two picklists; " + parent.path("api_name").asText() + " is " + parent.path("data_type").asText() + ", " + child.path("api_name").asText() + " is " + child.path("data_type").asText() + ".");
            }
            if (crm != null && !dryRun) {
                JsonNode have = crm.call("GET", "/crm/" + v + "/settings/layouts/" + layoutId + "/map_dependency?module=" + enc(module), null).json;
                if (ImplDependencies.existing(have, parent.path("api_name").asText(), child.path("api_name").asText()) != null) {
                    r.put("result", "skipped"); r.put("detail", "These two fields are already mapped on this layout (change it in Setup)."); return;
                }
            }
            JsonNode mapping = d.path("mapping");
            List<String> addedP = ImplDependencies.missing(parent, ImplDependencies.parentValues(mapping));
            List<String> addedC = ImplDependencies.missing(child, ImplDependencies.childValues(mapping));
            for (String val : addedP) ensurePicklistValue(module, parent, MAPPER.getNodeFactory().textNode(val), requests);
            for (String val : addedC) ensurePicklistValue(module, child, MAPPER.getNodeFactory().textNode(val), requests);
            if (!dryRun && crm != null && (!addedP.isEmpty() || !addedC.isEmpty())) {
                JsonNode fj = crm.call("GET", "/crm/" + v + "/settings/fields?module=" + enc(module), null).json;
                if (fj != null) for (JsonNode f : fj.path("fields")) {
                    if (f.path("api_name").asText().equals(parent.path("api_name").asText())) parent = f;
                    if (f.path("api_name").asText().equals(child.path("api_name").asText())) child = f;
                }
            }
            ObjectNode body = ImplDependencies.payload(parent, child, mapping);
            if (dryRun) {   // placeholders for options that don't exist yet
                ArrayNode pvs = ((ObjectNode) body.path("map_dependency").path(0)).withArray("pick_list_values");
                if (pvs.size() == 0) pvs.addObject().put("id", "<parent option ids>").putArray("maps").addObject().put("id", "<child option ids>");
            }
            call(requests, "POST", "/crm/" + v + "/settings/layouts/" + layoutId + "/map_dependency?module=" + enc(module), body, "map_dependency", "picklist dependency");
            r.put("result", dryRun ? "would_create" : "created");
            r.put("detail", (dryRun ? "Would map " : "Mapped ") + child.path("api_name").asText() + " to " + parent.path("api_name").asText() + " for " + mapping.size() + " parent option(s)"
                    + (addedP.isEmpty() && addedC.isEmpty() ? "" : "; added options " + String.join(", ", addedP) + (addedP.isEmpty() || addedC.isEmpty() ? "" : ", ") + String.join(", ", addedC)) + ".");
        }

        /** A live field of the module by api name or label, including ones created earlier in this run (re-read). */
        JsonNode liveField(String module, String apiOrLabel) throws Exception {
            JsonNode f = org == null ? null : org.field(module, apiOrLabel);
            if (f != null || crm == null || dryRun) return f;
            JsonNode fj = crm.call("GET", "/crm/" + v + "/settings/fields?module=" + enc(module), null).json;
            if (fj == null) return null;
            for (JsonNode x : fj.path("fields")) {
                if (x.path("api_name").asText().equalsIgnoreCase(apiOrLabel) || x.path("field_label").asText().equalsIgnoreCase(apiOrLabel)) return x;
            }
            return null;
        }

        /**
         * Deal pipeline through POST settings/pipeline?layout_id: stages are Stage picklist values in order
         * (missing ones added to the picklist first). New stages land with Zoho's default forecast category;
         * probabilities are a Setup follow-up.
         */
        void pipeline(JsonNode wf, ObjectNode r) throws Exception {
            String module = moduleApi(wf.path("module").asText("Deals"));
            List<String> stages = ImplBlueprint.states(wf);
            if (stages.size() < 2) throw new IllegalStateException("A pipeline needs at least two stages; the spec lists " + stages.size() + ".");
            ArrayNode requests = r.putArray("requests");
            JsonNode layouts = crm == null ? null : crm.call("GET", "/crm/" + v + "/settings/layouts?module=" + enc(module), null).json;
            JsonNode layout = null;
            if (layouts != null) for (JsonNode l : layouts.path("layouts")) if (layout == null || l.path("name").asText("").equalsIgnoreCase("Standard")) layout = l;
            if (layout == null && !dryRun) throw new IllegalStateException("Couldn't read the layouts of " + module + ".");
            String layoutId = layout == null ? "<layout id>" : layout.path("id").asText();
            if (crm != null && !dryRun) {
                JsonNode have = crm.call("GET", "/crm/" + v + "/settings/pipeline?layout_id=" + layoutId, null).json;
                if (ImplPipeline.existing(have, wf.path("name").asText()) != null) { r.put("result", "skipped"); r.put("detail", "A pipeline with this name already exists."); return; }
            }
            JsonNode field = org == null ? null : org.field(module, "Stage");
            if (field == null && crm != null) {
                JsonNode fj = crm.call("GET", "/crm/" + v + "/settings/fields?module=" + enc(module), null).json;
                if (fj != null) for (JsonNode f : fj.path("fields")) if ("Stage".equals(f.path("api_name").asText())) field = f;
            }
            if (field == null) {
                if (!dryRun) throw new IllegalStateException(module + " has no Stage field; pipelines are a Deals feature.");
                field = MAPPER.createObjectNode().put("api_name", "Stage").put("id", "<Stage id>").put("data_type", "picklist");
            }
            List<String> added = new ArrayList<>();
            for (String s : stages) {
                boolean has = false;
                for (JsonNode pv : field.path("pick_list_values")) if (s.equalsIgnoreCase(pv.path("actual_value").asText()) || s.equalsIgnoreCase(pv.path("display_value").asText())) has = true;
                if (!has) { ensurePicklistValue(module, field, MAPPER.getNodeFactory().textNode(s), requests); added.add(s); }
            }
            Map<String, String> ids = ImplBlueprint.optionIds(field, stages);
            if (!dryRun && ids.size() < stages.size() && crm != null) {
                JsonNode fj = crm.call("GET", "/crm/" + v + "/settings/fields/" + field.path("id").asText() + "?module=" + enc(module), null).json;
                if (fj != null && fj.path("fields").size() > 0) { field = fj.path("fields").path(0); ids = ImplBlueprint.optionIds(field, stages); }
            }
            if (!dryRun && ids.size() < stages.size()) {
                List<String> missing = new ArrayList<>(stages); missing.removeAll(ids.keySet());
                throw new IllegalStateException("Stage has no option for " + missing + " even after adding them.");
            }
            if (dryRun) for (String s : stages) ids.putIfAbsent(s, "<id of stage " + s + ">");
            ObjectNode body = ImplPipeline.payload(wf, stages, ids);
            call(requests, "POST", "/crm/" + v + "/settings/pipeline?layout_id=" + layoutId, body, "pipeline", "pipeline");
            r.put("result", dryRun ? "would_create" : "created");
            r.put("detail", (dryRun ? "Would create " : "Created ") + "pipeline \"" + wf.path("name").asText() + "\" with " + stages.size() + " stages" + (added.isEmpty() ? "" : " (" + added.size() + " new Stage value(s): " + String.join(", ", added) + ")") + ".");
            if (!added.isEmpty()) r.put("follow_up", "New stages have Zoho's default forecast category and probability; set them in Setup → Customization → Modules and Fields → Deals → Stage-Probability Mapping.");
        }

        /**
         * Blueprint through POST settings/blueprints: states mapped to the picklist's options (options added
         * first when missing), one manual transition per consecutive pair unless the spec names transitions,
         * chart laid out in a row. Created in Draft, then POST …/actions/activate; when activation is refused
         * the draft stays and the person publishes it in Setup.
         */
        void blueprint(JsonNode wf, ObjectNode r) throws Exception {
            String module = moduleApi(wf.path("module").asText());
            String moduleId = org == null ? "" : org.moduleId(module);
            if (moduleId.isEmpty() && !dryRun) throw new IllegalStateException("Module " + module + " isn't in the org.");
            List<String> states = ImplBlueprint.states(wf);
            if (states.size() < 2) throw new IllegalStateException("A Blueprint needs at least two states; the spec lists " + states.size() + ".");
            ArrayNode requests = r.putArray("requests");

            JsonNode layouts = crm == null ? null : crm.call("GET", "/crm/" + v + "/settings/layouts?module=" + enc(module), null).json;
            JsonNode layout = null;
            if (layouts != null) for (JsonNode l : layouts.path("layouts")) if (layout == null || l.path("name").asText("").equalsIgnoreCase("Standard")) layout = l;
            if (layout == null) {
                if (!dryRun) throw new IllegalStateException("Couldn't read the layouts of " + module + ".");
                layout = MAPPER.createObjectNode().put("id", "<layout id>").put("name", "Standard").put("api_name", "Standard");
            }

            List<JsonNode> fields = new ArrayList<>();
            if (org != null && org.fields.get(ImplSpec.low(module)) != null) fields.addAll(org.fields.get(ImplSpec.low(module)));
            if (fields.isEmpty() && crm != null) {
                JsonNode fj = crm.call("GET", "/crm/" + v + "/settings/fields?module=" + enc(module), null).json;
                if (fj != null) for (JsonNode f : fj.path("fields")) fields.add(f);
            }
            JsonNode field = ImplBlueprint.pickField(wf, fields, states);
            if (field == null) throw new IllegalStateException("No picklist on " + module + " matches the states " + states
                    + "; name the field in the spec (field: api name) or add the picklist first.");
            for (String s : states) ensurePicklistValue(module, field, MAPPER.getNodeFactory().textNode(s), requests);
            Map<String, String> optionIds = ImplBlueprint.optionIds(field, states);
            if (!dryRun && optionIds.size() < states.size() && crm != null) {
                // options were just added: re-read the field for their ids
                JsonNode fj = crm.call("GET", "/crm/" + v + "/settings/fields/" + field.path("id").asText() + "?module=" + enc(module), null).json;
                if (fj != null && fj.path("fields").size() > 0) { field = fj.path("fields").path(0); optionIds = ImplBlueprint.optionIds(field, states); }
            }
            if (!dryRun && optionIds.size() < states.size()) {
                List<String> missing = new ArrayList<>(states); missing.removeAll(optionIds.keySet());
                throw new IllegalStateException("Picklist " + field.path("api_name").asText() + " has no option for " + missing + ".");
            }

            ObjectNode body = ImplBlueprint.payload(wf, module, moduleId, layout, field, states, optionIds);
            r.put("field", field.path("api_name").asText());
            String id = call(requests, "POST", "/crm/" + v + "/settings/blueprints", body, "blueprints", "blueprint");
            r.put("result", dryRun ? "would_create" : "created");
            r.put("detail", (dryRun ? "Would create " : "Created ") + "the Blueprint on " + field.path("api_name").asText() + " with " + states.size()
                    + " states and " + body.path("blueprints").path(0).path("transitions").size() + " transition(s) (Draft).");
            if (dryRun) { r.put("follow_up", "Then publishes it: POST settings/blueprints/{id}/actions/activate."); return; }
            try {
                call(requests, "POST", "/crm/" + v + "/settings/blueprints/" + id + "/actions/activate", ImplBlueprint.activateBody(), "blueprints", "activate blueprint");
                r.put("detail", r.path("detail").asText().replace("(Draft)", "— published"));
            } catch (ImplDiagnose.StepFailure e) {
                r.put("follow_up", "Created as a Draft; publishing was refused (" + ImplementServlet.trim(e.getMessage(), 200)
                        + "). Open Setup → Process Management → Blueprint → \"" + wf.path("name").asText() + "\", check the chart and click Publish.");
            }
        }

        /**
         * Deluge function through POST settings/functions (multipart, "metadata" part). With its code in the
         * metadata the function is published at once; the rule that calls it is attached in Setup.
         */
        void function(JsonNode fn, ObjectNode r) throws Exception {
            String api = ImplFunctions.apiName(fn);
            if (org != null && Boolean.TRUE.equals(org.hasFunction(api))) {
                r.put("result", "skipped"); r.put("detail", "A function named " + api + " already exists."); return;
            }
            ObjectNode payload = ImplFunctions.metadata(fn);
            ObjectNode preview = r.putObject("request");
            preview.put("method", "POST");
            preview.put("path", "/crm/" + v + "/settings/functions");
            preview.put("content_type", "multipart/form-data; part \"metadata\"");
            preview.set("body", payload);
            r.put("api_name", api);
            r.put("category", payload.path("functions").path(0).path("category").asText());
            if (dryRun) { r.put("result", "would_create"); return; }
            Map<String, String> parts = new LinkedHashMap<>();
            parts.put("metadata", MAPPER.writeValueAsString(payload));
            CrmClient.Response res = crm.callMultipart("POST", "/crm/" + v + "/settings/functions", parts);
            JsonNode first = res.json == null ? null : res.json.path("functions").path(0);
            String code = first == null ? "" : first.path("code").asText("");
            preview.put("status", res.statusCode);
            if (res.statusCode >= 400 || (!code.isEmpty() && !"SUCCESS".equalsIgnoreCase(code))) {
                String msg = first != null && first.has("message") ? first.path("message").asText() + " " + first.path("details").toString() : res.rawBody;
                if (res.json != null) preview.set("response", res.json); else preview.put("response", ImplementServlet.trim(res.rawBody, 2000));
                throw new ImplDiagnose.StepFailure("HTTP " + res.statusCode + ": " + ImplementServlet.trim(msg, 300),
                        "POST", "/crm/" + v + "/settings/functions", payload, res.statusCode, res.rawBody);
            }
            r.put("result", "created");
            if (first != null && first.path("details").has("id")) r.put("function_id", first.path("details").path("id").asText());
            r.put("detail", "Created and published " + api + " (" + r.path("category").asText() + ").");
            if (org != null && org.functions != null) org.functions.add(ImplSpec.low(api));
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
            preview.put("status", res.statusCode);
            if (res.statusCode >= 400 || (!code.isEmpty() && !"SUCCESS".equalsIgnoreCase(code))) {
                String msg = first != null && first.has("message") ? first.path("message").asText() + " "
                        + first.path("details").toString() : res.rawBody;
                if (res.json != null) preview.set("response", res.json); else preview.put("response", ImplementServlet.trim(res.rawBody, 2000));
                throw new ImplDiagnose.StepFailure("HTTP " + res.statusCode + ": " + ImplementServlet.trim(msg, 300),
                        method, path, payload, res.statusCode, res.rawBody);
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

    /** Module labels: letters, numbers and spaces, at most 25 characters. */
    static String moduleLabel(String s) {
        String t = (s == null ? "" : s).replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 25 ? t.substring(0, 25).trim() : t;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }
}
