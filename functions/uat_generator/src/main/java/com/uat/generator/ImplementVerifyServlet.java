package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /implement/verify — step 4 of the implementation pipeline (Verifier).
 *
 * Body: { "spec": { ... } }
 *
 * Reads the connected org's metadata back (ImplOrg, read-only) and diffs it against
 * every included item:
 *   present       found and matches
 *   mismatch      found, but differs (data type, missing picklist values, lookup target)
 *   missing       not found
 *   unverifiable  the REST metadata APIs used here can't read it back (validation rules,
 *                 workflows, Deluge functions) — check in Setup
 */
public class ImplementVerifyServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ImplementVerifyServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);
        try {
            JsonNode spec = MAPPER.readTree(req.getInputStream()).path("spec");
            if (!spec.isObject()) {
                ImplementServlet.writeError(resp, 400, "Field 'spec' (object from /implement) is required.");
                return;
            }
            CrmClient crm = CrmMetadata.sessionClient(req);
            if (crm == null) {
                ImplementServlet.writeError(resp, 401, "Connect Zoho CRM first — verify reads the org's metadata.");
                return;
            }
            ImplOrg org = ImplOrg.read(crm, ImplementApplyServlet.modulesOf(spec), true);

            ArrayNode results = MAPPER.createArrayNode();
            for (String section : new String[]{"modules", "fields", "layouts", "roles", "validation_rules",
                                               "workflows", "functions"}) {
                for (JsonNode item : spec.path(section)) {
                    if (!item.path("include").asBoolean(true) && !"exists".equals(item.path("status").asText())) continue;
                    ObjectNode r = results.addObject();
                    r.put("id", item.path("id").asText());
                    r.put("section", section);
                    r.put("label", ImplPlan.describe(section, item));
                    check(section, item, org, r);
                }
            }

            Map<String, Integer> counts = new LinkedHashMap<>();
            for (JsonNode r : results) counts.merge(r.path("verdict").asText(), 1, Integer::sum);
            ObjectNode out = MAPPER.createObjectNode();
            ObjectNode o = out.putObject("org");
            o.put("id", org.orgId);
            o.put("name", org.orgName);
            o.put("sandbox", org.sandbox);
            ObjectNode c = out.putObject("counts");
            counts.forEach(c::put);
            ArrayNode errs = out.putArray("warnings");
            for (String e : org.errors) errs.add("Couldn't read " + e);
            out.set("results", results);
            resp.setStatus(200);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "implement/verify failed", e);
            ImplementServlet.writeError(resp, 500, e.getMessage());
        }
    }

    static void check(String section, JsonNode item, ImplOrg org, ObjectNode r) {
        switch (section) {
            case "modules":
                r.put("verdict", org.hasModule(item) ? "present" : "missing");
                return;
            case "roles":
                r.put("verdict", org.hasRole(item.path("name").asText()) ? "present" : "missing");
                return;
            case "layouts": {
                Boolean has = org.hasLayout(item.path("module").asText(), item.path("name").asText());
                if (has == null) { r.put("verdict", "unverifiable"); r.put("detail", "Layouts of this module couldn't be read."); }
                else r.put("verdict", has ? "present" : "missing");
                return;
            }
            case "fields": {
                String module = item.path("module").asText();
                if (!org.knowsFieldsOf(module)) {
                    r.put("verdict", org.moduleApiName(module) == null ? "missing" : "unverifiable");
                    r.put("detail", org.moduleApiName(module) == null ? "Module " + module + " doesn't exist." : "Fields couldn't be read.");
                    return;
                }
                JsonNode live = org.findField(item);
                if (live == null) { r.put("verdict", "missing"); return; }
                List<String> diffs = fieldDiffs(item, live);
                r.put("verdict", diffs.isEmpty() ? "present" : "mismatch");
                r.put("api_name", live.path("api_name").asText());
                if (!diffs.isEmpty()) r.put("detail", String.join("; ", diffs));
                return;
            }
            default:
                r.put("verdict", "unverifiable");
                r.put("detail", "Not readable through the metadata APIs used here; check in Setup.");
        }
    }

    static List<String> fieldDiffs(JsonNode spec, JsonNode live) {
        List<String> d = new ArrayList<>();
        String why = ImplCompare.conflict(spec, live);
        if (why != null) d.add(why);
        List<String> missing = ImplCompare.missingPicklistValues(spec, live);
        if (!missing.isEmpty()) d.add("Picklist is missing " + String.join(", ", missing) + ".");
        return d;
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }
}
