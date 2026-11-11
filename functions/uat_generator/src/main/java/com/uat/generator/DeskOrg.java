package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a Zoho Desk org into the same neutral {@link ImplOrg} model the pipeline uses for CRM, so
 * comparison, status marking and the agent's org text work unchanged.
 *
 * Desk API (read-only here; the OAuth token is the same Zoho token, with Desk.* scopes):
 *   GET {desk}/api/v1/organizations                     orgs the user can reach
 *   GET {desk}/api/v1/departments                       departments → listed as "existing roles"-style context
 *   GET {desk}/api/v1/organizationFields?module=X       fields of tickets / contacts / accounts / tasks …
 * Every call carries the orgId header. The Desk host follows the CRM API host's data centre.
 */
public final class DeskOrg {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final List<String> MODULES = Arrays.asList("Tickets", "Contacts", "Accounts", "Tasks", "Calls", "Events", "Products");

    private DeskOrg() {}

    /** desk host for the client's API host: www.zohoapis.in → https://desk.zoho.in */
    static String deskBase(CrmClient crm) {
        String env = System.getenv("DESK_API_BASE");
        if (env != null && !env.isEmpty()) return env.replaceAll("/+$", "");
        String api = crm.apiBase();
        String tld = api.replaceAll("^https?://[^.]+\\.zohoapis", "").replaceAll("/.*$", "");
        if (tld.isEmpty() || !tld.startsWith(".")) tld = ".com";
        return "https://desk.zoho" + tld;
    }

    /**
     * Prepares a client for Desk record calls: Desk host, orgId header, and the variables UAT plans may
     * use before capturing anything: {{departmentId}} (the default department) and {{orgId}}.
     * Returns the seeds; the client is modified in place.
     */
    public static java.util.Map<String, String> prepare(CrmClient crm) {
        java.util.Map<String, String> seeds = new java.util.LinkedHashMap<>();
        String base = deskBase(crm);
        try {
            JsonNode orgs = get(crm, base + "/api/v1/organizations").path("data");
            JsonNode org = null;
            String wantOrg = System.getenv("DESK_ORG_ID") == null ? "" : System.getenv("DESK_ORG_ID");
            for (JsonNode n : orgs) if (org == null || n.path("id").asText().equals(wantOrg) || (wantOrg.isEmpty() && n.path("isDefault").asBoolean(false))) org = n;
            if (org != null) { crm.withHeader("orgId", org.path("id").asText("")); seeds.put("orgId", org.path("id").asText("")); }
            JsonNode deps = get(crm, base + "/api/v1/departments?isEnabled=true").path("data");
            JsonNode dep = null;
            for (JsonNode d : deps) if (dep == null || d.path("isDefault").asBoolean(false)) dep = d;
            if (dep != null) { seeds.put("departmentId", dep.path("id").asText("")); seeds.put("departmentName", dep.path("name").asText("")); }
        } catch (Exception ignored) { /* the first UAT step will report the real error */ }
        return seeds;
    }

    /** A client for Desk record calls built from a CRM-shaped one: same OAuth, Desk host. */
    public static CrmClient deskClient(CrmClient base, String accountsBase, String clientId, String clientSecret, String refreshToken, String accessToken) {
        String host = deskBase(base);
        CrmClient c = accessToken != null && !accessToken.isEmpty() ? CrmClient.withAccessToken(host, accessToken)
                : new CrmClient(host, accountsBase, clientId, clientSecret, refreshToken);
        return c;
    }

    public static ImplOrg read(CrmClient crm, Collection<String> moduleNames, boolean withLayouts) {
        ImplOrg o = new ImplOrg();
        String base = deskBase(crm);
        String wantOrg = System.getenv("DESK_ORG_ID") == null ? "" : System.getenv("DESK_ORG_ID");
        try {
            JsonNode orgs = get(crm, base + "/api/v1/organizations").path("data");
            JsonNode org = null;
            for (JsonNode n : orgs) if (org == null || n.path("id").asText().equals(wantOrg) || (wantOrg.isEmpty() && n.path("isDefault").asBoolean(false))) org = n;
            if (org == null) throw new IllegalStateException("no Desk organization is reachable with this connection");
            o.orgId = org.path("id").asText("");
            o.orgName = org.path("companyName").asText(org.path("portalName").asText(""));
            o.orgType = "production";
            o.sandbox = false;
            crm.withHeader("orgId", o.orgId);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            o.errors.add("desk org: " + msg + (msg.contains("OAUTH_SCOPE") || msg.contains("401") || msg.contains("INVALID_OAUTH")
                    ? " — reconnect so the token includes the Desk scopes (Desk.basic.READ, Desk.settings.READ)." : ""));
            return o;
        }
        try {
            for (JsonNode d : get(crm, base + "/api/v1/departments?isEnabled=true").path("data")) {
                o.roles.add("department: " + d.path("name").asText(""));
            }
        } catch (Exception e) { o.errors.add("departments: " + e.getMessage()); }

        Set<String> names = new LinkedHashSet<>(MODULES);
        for (String m : MODULES) {
            ObjectNode mod = MAPPER.createObjectNode().put("api_name", m).put("plural_label", m)
                    .put("singular_label", m.endsWith("s") ? m.substring(0, m.length() - 1) : m).put("generated_type", "default").put("api_supported", true);
            o.modules.putIfAbsent(ImplSpec.low(m), mod);
        }
        for (String name : moduleNames) if (name != null && !name.isEmpty()) names.add(name);
        for (String name : names) {
            String api = o.moduleApiName(name);
            if (api == null) continue;
            try {
                List<JsonNode> list = new java.util.ArrayList<>();
                for (JsonNode f : get(crm, base + "/api/v1/organizationFields?module=" + api.toLowerCase(Locale.ROOT)).path("data")) {
                    list.add(toCrmField(f));
                }
                o.fields.put(ImplSpec.low(api), list);
            } catch (Exception e) {
                if (moduleNames.contains(name)) o.errors.add("fields of " + name + ": " + e.getMessage());
            }
        }
        return o;
    }

    /** Desk field → the CRM-shaped field JSON the pipeline already understands. */
    static JsonNode toCrmField(JsonNode f) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("id", f.path("id").asText(""));
        c.put("api_name", f.path("apiName").asText(""));
        c.put("field_label", f.path("displayLabel").asText(f.path("name").asText("")));
        String type = f.path("type").asText("").toLowerCase(Locale.ROOT);
        String crmType = type.contains("picklist") || type.equals("select") ? (type.contains("multi") ? "multiselectpicklist" : "picklist")
                : type.contains("multiline") || type.equals("textarea") ? "textarea"
                : type.equals("email") ? "email" : type.equals("phone") ? "phone" : type.equals("url") ? "website"
                : type.equals("date") ? "date" : type.contains("datetime") ? "datetime"
                : type.equals("number") || type.equals("integer") ? "integer" : type.equals("decimal") || type.equals("double") ? "decimal"
                : type.equals("currency") ? "currency" : type.equals("percent") ? "percent" : type.equals("boolean") || type.equals("checkbox") ? "boolean"
                : type.equals("lookup") ? "lookup" : "text";
        c.put("data_type", crmType);
        c.put("custom_field", f.path("isCustomField").asBoolean(false));
        if (f.has("maxLength")) c.put("length", f.path("maxLength").asInt(0));
        ArrayNode pv = c.putArray("pick_list_values");
        for (JsonNode v : f.path("allowedValues")) {
            String s = v.isObject() ? v.path("value").asText(v.path("name").asText("")) : v.asText("");
            if (!s.isEmpty()) pv.addObject().put("display_value", s).put("actual_value", s);
        }
        return c;
    }

    private static JsonNode get(CrmClient crm, String url) throws Exception {
        CrmClient.Response r = crm.call("GET", url, null);
        if (r.statusCode == 204) return MAPPER.createObjectNode();
        if (r.statusCode >= 400 || r.json == null) {
            String body = r.rawBody == null ? "" : r.rawBody;
            throw new IllegalStateException("HTTP " + r.statusCode + (body.isEmpty() ? "" : ": " + (body.length() > 160 ? body.substring(0, 160) + "…" : body)));
        }
        return r.json;
    }
}
