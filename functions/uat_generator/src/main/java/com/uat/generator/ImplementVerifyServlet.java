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
 *                 blueprints/approvals, Deluge functions) — check in Setup. Workflow rules are
 *                 checked by name (their actions aren't compared).
 */
public class ImplementVerifyServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ImplementVerifyServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);
        try {
            JsonNode vbody = MAPPER.readTree(req.getInputStream());
            Products.use(vbody);
            JsonNode spec = vbody.path("spec");
            if (!spec.isObject()) {
                ImplementServlet.writeError(resp, 400, "Field 'spec' (object from /implement) is required.");
                return;
            }
            CrmClient crm = CrmMetadata.sessionClient(req);
            if (crm == null) {
                ImplementServlet.writeError(resp, 401, "Connect Zoho CRM first — verify reads the org's metadata.");
                return;
            }
            ImplOrg org = Products.current().readOrg(crm, ImplementApplyServlet.modulesOf(spec), true);

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
            o.put("allow_listed", org.allowListed);
            o.put("production_allowed", org.productionAllowed);
            o.put("zgid", org.zgid);
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

    /**
     * Sets verdict, plus "expected" (what the blueprint says) and "actual" (what the org has)
     * as short readable text, and "fixable" when /implement/apply can repair it.
     */
    static void check(String section, JsonNode item, ImplOrg org, ObjectNode r) {
        switch (section) {
            case "modules": {
                boolean has = org.hasModule(item);
                r.put("verdict", has ? "present" : "missing");
                r.put("expected", "module " + item.path("plural_label").asText());
                r.put("actual", has ? "present" : "not found");
                r.put("fixable", !has);
                return;
            }
            case "roles": {
                boolean has = org.hasRole(item.path("name").asText());
                r.put("verdict", has ? "present" : "missing");
                r.put("expected", "role " + item.path("name").asText()
                        + (item.path("reports_to").asText("").isEmpty() ? "" : ", under " + item.path("reports_to").asText()));
                r.put("actual", has ? "present" : "not found");
                r.put("fixable", !has);
                return;
            }
            case "layouts": {
                Boolean has = org.hasLayout(item.path("module").asText(), item.path("name").asText());
                r.put("expected", "layout " + item.path("name").asText());
                if (has == null) {
                    r.put("verdict", "unverifiable");
                    r.put("actual", "couldn't read layouts");
                    r.put("detail", "Layouts of this module couldn't be read.");
                } else {
                    r.put("verdict", has ? "present" : "missing");
                    r.put("actual", has ? "present (sections not compared)" : "not found");
                }
                return;
            }
            case "fields": {
                String module = item.path("module").asText();
                r.put("expected", fieldText(item.path("data_type").asText(), item.path("picklist_values"),
                        item.path("lookup_module").asText("")));
                if (!org.knowsFieldsOf(module)) {
                    boolean noModule = org.moduleApiName(module) == null;
                    r.put("verdict", noModule ? "missing" : "unverifiable");
                    r.put("actual", noModule ? "module " + module + " not found" : "couldn't read fields");
                    r.put("fixable", false);
                    return;
                }
                JsonNode live = org.findField(item);
                if (live == null) {
                    r.put("verdict", "missing");
                    r.put("actual", "not found");
                    r.put("fixable", ImplSpec.API_FIELD_TYPES.contains(item.path("data_type").asText()));
                    return;
                }
                List<String> diffs = fieldDiffs(item, live);
                r.put("verdict", diffs.isEmpty() ? "present" : "mismatch");
                r.put("api_name", live.path("api_name").asText());
                r.put("actual", fieldText(ImplCompare.liveType(live.path("data_type").asText()),
                        live.path("pick_list_values"), live.path("lookup").path("module").path("api_name").asText("")));
                // Missing picklist values can be added; a type or lookup conflict can't be changed in place.
                r.put("fixable", !diffs.isEmpty() && ImplCompare.conflict(item, live) == null);
                if (!diffs.isEmpty()) r.put("detail", String.join("; ", diffs));
                return;
            }
            case "workflows": {
                r.put("expected", "rule " + ImplRules.ruleName(item.path("name").asText()));
                Boolean has = "workflow".equals(item.path("type").asText())
                        ? org.hasWorkflow(item.path("module").asText(), item.path("name").asText()) : null;
                if (has != null) {
                    r.put("verdict", has ? "present" : "missing");
                    r.put("actual", has ? "present (actions not compared)" : "not found");
                    r.put("fixable", !has && !"rpa".equals(item.path("executor").asText()));
                    return;
                }
                r.put("verdict", "unverifiable");
                r.put("actual", "check in Setup");
                r.put("detail", "Only workflow rules are read back; check " + item.path("type").asText() + "s in Setup.");
                return;
            }
            default:
                r.put("verdict", "unverifiable");
                r.put("expected", ImplPlan.describe(section, item));
                r.put("actual", "check in Setup");
                r.put("detail", "Not readable through the metadata APIs used here; check in Setup.");
        }
    }

    /** "picklist: A, B, C" / "lookup to Deals" / "decimal". */
    static String fieldText(String type, JsonNode picks, String lookup) {
        StringBuilder sb = new StringBuilder(type);
        if (!lookup.isEmpty()) sb.append(" to ").append(lookup);
        List<String> vals = new ArrayList<>();
        for (JsonNode p : picks) {
            String v = p.isObject() ? p.path("display_value").asText(p.path("actual_value").asText("")) : p.asText("");
            if (!v.isEmpty() && !"-None-".equals(v)) vals.add(v);
            if (vals.size() == 8) { vals.add("…"); break; }
        }
        if (!vals.isEmpty()) sb.append(": ").append(String.join(", ", vals));
        return sb.toString();
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
