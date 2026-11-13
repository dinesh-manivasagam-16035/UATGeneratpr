package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A read of the connected org's metadata, used to tell new items from existing
 * ones when generating, and to diff the org against the spec when verifying.
 *
 * Sources (all read-only):
 *   GET /crm/v3/org                         org id, name, type (sandbox / production)
 *   GET /crm/v3/settings/modules            modules
 *   GET /crm/v3/settings/fields?module=X    fields, per module asked for
 *   GET /crm/v3/settings/layouts?module=X   layout names, per module asked for
 *   GET /crm/v3/settings/roles              roles
 *   GET /crm/v8/settings/automation/workflow_rules?module=X   workflow rule names, per module
 * A section that can't be read is left empty and listed in errors.
 */
public final class ImplOrg {

    public String orgId = "";
    public String orgName = "";
    public String orgType = "";
    /** IANA time zone of the org ("Asia/Kolkata"), for time-based rules' execute_at. */
    public String timeZone = "";
    public boolean sandbox;
    /** lower-cased api_name / singular / plural label → module JSON */
    public final Map<String, JsonNode> modules = new LinkedHashMap<>();
    /** module api_name (as asked) → fields JSON */
    public final Map<String, List<JsonNode>> fields = new LinkedHashMap<>();
    public final Map<String, List<String>> layouts = new LinkedHashMap<>();
    public final List<String> roles = new ArrayList<>();
    /** lower-cased module api_name → workflow rule names (only for modules that were read). */
    public final Map<String, List<String>> workflows = new LinkedHashMap<>();
    /** lower-cased api_name and name of every Deluge function in the org (null until read). */
    public List<String> functions;
    /** lower-cased api_name and display_label of the org's global picklists (null until read). */
    public List<String> globalPicklists;
    /** lower-cased module api_name → email template names (only for modules that were read). */
    public final Map<String, List<String>> emailTemplates = new LinkedHashMap<>();
    public final List<String> errors = new ArrayList<>();
    /** True when the org counts as buildable only because IMPL_BUILD_ORG_IDS names it. */
    public boolean allowListed;
    /** True when Apply is allowed only because IMPL_ALLOW_PRODUCTION is set. */
    public boolean productionAllowed;
    public String zgid = "";
    public String domain = "";

    /** Matches IMPL_BUILD_ORG_IDS against any of the org's ids (CRM id, ZGID, domain). "*" allows every org. */
    static boolean allowListed(String... orgIds) {
        String ids = System.getenv("IMPL_BUILD_ORG_IDS");
        if (ids == null) return false;
        for (String id : ids.split("[,;\\s]+")) {
            if (id.isEmpty()) continue;
            if ("*".equals(id)) return true;
            for (String mine : orgIds) if (mine != null && !mine.trim().isEmpty() && id.equalsIgnoreCase(mine.trim())) return true;
        }
        return false;
    }

    /**
     * IMPL_ALLOW_PRODUCTION=1 on uat_generator lets Build write to any connected org, production
     * included. The UI then asks for the org name to be typed before applying. Off by default.
     */
    static boolean productionAllowed() {
        String v = System.getenv("IMPL_ALLOW_PRODUCTION");
        return v != null && ("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()) || "yes".equalsIgnoreCase(v.trim()));
    }

    ImplOrg() {}

    public static ImplOrg read(CrmClient crm, Collection<String> moduleNames, boolean withLayouts) {
        ImplOrg o = new ImplOrg();
        try {
            JsonNode org = get(crm, "/crm/v3/org").path("org").path(0);
            o.orgId = org.path("id").asText("");
            o.zgid = org.path("zgid").asText("");
            o.domain = org.path("domain_name").asText("");
            o.orgName = org.path("company_name").asText("");
            o.orgType = org.path("type").asText("");
            o.timeZone = org.path("time_zone").asText("");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            o.errors.add("org: " + msg + (msg.contains("OAUTH_SCOPE_MISMATCH")
                    ? " — the CRM connection lacks the ZohoCRM.org.READ scope, so LiftOff can't tell whether this is a "
                      + "sandbox and keeps Apply off. Disconnect and reconnect CRM to grant it."
                    : ""));
        }
        String t = o.orgType.toLowerCase(Locale.ROOT);
        o.sandbox = t.contains("sandbox") || t.contains("developer")
                || crm.apiBase().toLowerCase(Locale.ROOT).contains("sandbox");
        // Test orgs that aren't Zoho sandboxes can be allow-listed by org id on uat_generator
        // (IMPL_BUILD_ORG_IDS="600...,600..."): Build treats them like a sandbox. Production
        // orgs are never written to unless someone lists them here on purpose.
        if (!o.sandbox && !o.orgId.isEmpty() && allowListed(o.orgId, o.zgid, o.domain)) {
            o.sandbox = true;
            o.allowListed = true;
        }
        if (!o.sandbox && !o.orgId.isEmpty() && productionAllowed()) {
            o.sandbox = true;
            o.productionAllowed = true;
        }

        try {
            for (JsonNode m : get(crm, "/crm/v3/settings/modules").path("modules")) {
                for (String k : new String[]{"api_name", "singular_label", "plural_label", "module_name"}) {
                    String v = m.path(k).asText("");
                    if (!v.isEmpty()) o.modules.putIfAbsent(ImplSpec.low(v), m);
                }
            }
        } catch (Exception e) {
            o.errors.add("modules: " + e.getMessage());
        }

        for (String name : moduleNames) {
            String api = o.moduleApiName(name);
            if (api == null) continue;           // module doesn't exist yet — nothing to read
            try {
                List<JsonNode> list = new ArrayList<>();
                for (JsonNode f : get(crm, "/crm/v3/settings/fields?module=" + enc(api)).path("fields")) list.add(f);
                o.fields.put(ImplSpec.low(name), list);
                if (!ImplSpec.low(name).equals(ImplSpec.low(api))) o.fields.put(ImplSpec.low(api), list);
            } catch (Exception e) {
                o.errors.add("fields of " + api + ": " + e.getMessage());
            }
            try {
                List<String> names = new ArrayList<>();
                JsonNode wr = get(crm, "/crm/v8/settings/automation/workflow_rules?module=" + enc(api)).path("workflow_rules");
                for (JsonNode w : wr) names.add(w.path("name").asText(""));
                o.workflows.put(ImplSpec.low(name), names);
                if (!ImplSpec.low(name).equals(ImplSpec.low(api))) o.workflows.put(ImplSpec.low(api), names);
                List<String> tpls = new ArrayList<>();
                JsonNode et = get(crm, "/crm/v8/settings/email_templates?module=" + enc(api)).path("email_templates");
                for (JsonNode tp : et) if (!"draft".equals(tp.path("category").asText(""))) tpls.add(tp.path("name").asText(""));
                o.emailTemplates.put(ImplSpec.low(api), tpls);
            } catch (Exception e) {
                // System modules (Actions_Performed, ...) have no workflow rules: not an error worth showing.
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (!msg.contains("INVALID_MODULE") && !msg.contains("not supported")) {
                    o.errors.add("workflow rules of " + api + ": " + msg);
                }
            }
            if (withLayouts) {
                try {
                    List<String> names = new ArrayList<>();
                    for (JsonNode l : get(crm, "/crm/v3/settings/layouts?module=" + enc(api)).path("layouts")) {
                        names.add(l.path("name").asText(""));
                    }
                    o.layouts.put(ImplSpec.low(name), names);
                } catch (Exception e) {
                    o.errors.add("layouts of " + api + ": " + e.getMessage());
                }
            }
        }

        try {
            for (JsonNode r : get(crm, "/crm/v3/settings/roles").path("roles")) {
                String n = r.path("name").asText(r.path("display_label").asText(""));
                if (!n.isEmpty()) o.roles.add(n);
            }
        } catch (Exception e) {
            o.errors.add("roles: " + e.getMessage());
        }
        try {
            JsonNode gps = get(crm, "/crm/v8/settings/global_picklists").path("global_picklists");
            if (gps.isArray()) {
                o.globalPicklists = new ArrayList<>();
                for (JsonNode g : gps) {
                    o.globalPicklists.add(ImplSpec.low(g.path("api_name").asText("")));
                    o.globalPicklists.add(ImplSpec.low(g.path("display_label").asText("")));
                }
            }
        } catch (Exception e) {
            o.errors.add("global picklists: " + e.getMessage());
        }
        try {
            JsonNode fns = get(crm, "/crm/v8/settings/functions?per_page=200").path("functions");
            if (fns.isArray()) {
                o.functions = new ArrayList<>();
                for (JsonNode f : fns) {
                    o.functions.add(ImplSpec.low(f.path("api_name").asText("")));
                    o.functions.add(ImplSpec.low(f.path("name").asText("")));
                }
            }
        } catch (Exception e) {
            o.errors.add("functions: " + e.getMessage());
        }
        return o;
    }

    /** The org's current UTC offset as "+05:30" (UTC when the zone is unknown). */
    public String utcOffset() {
        try {
            java.time.ZoneId z = java.time.ZoneId.of(timeZone.isEmpty() ? "UTC" : timeZone);
            String off = java.time.ZonedDateTime.now(z).getOffset().getId();
            return "Z".equals(off) ? "+00:00" : off;
        } catch (Exception e) { return "+00:00"; }
    }

    /** True/false when the org's global picklists were read, null when they weren't. */
    public Boolean hasGlobalPicklist(String name) {
        if (globalPicklists == null) return null;
        return globalPicklists.contains(ImplSpec.low(name));
    }

    /** True/false when the org's functions were read, null when they weren't. */
    public Boolean hasFunction(String apiName) {
        if (functions == null) return null;
        return functions.contains(ImplSpec.low(apiName));
    }

    /** The org's api_name for a module given by api name or label, or null when absent. */
    public String moduleApiName(String nameOrLabel) {
        JsonNode m = modules.get(ImplSpec.low(nameOrLabel));
        return m == null ? null : m.path("api_name").asText(nameOrLabel);
    }

    public boolean hasModule(JsonNode specModule) {
        return moduleApiName(specModule.path("api_name").asText("")) != null
            || moduleApiName(specModule.path("plural_label").asText("")) != null
            || moduleApiName(specModule.path("singular_label").asText("")) != null;
    }

    /** The org's module id, or "" when the module isn't known. */
    public String moduleId(String nameOrLabel) {
        JsonNode m = modules.get(ImplSpec.low(nameOrLabel));
        return m == null ? "" : m.path("id").asText("");
    }

    /** A live field of a module by api name (or label), or null. */
    public JsonNode field(String module, String apiOrLabel) {
        List<JsonNode> list = fields.get(ImplSpec.low(module));
        if (list == null) return null;
        String k = ImplSpec.low(apiOrLabel);
        for (JsonNode f : list) {
            if (ImplSpec.low(f.path("api_name").asText()).equals(k) || ImplSpec.low(f.path("field_label").asText()).equals(k)) return f;
        }
        return null;
    }

    /** True/false when the module's workflow rules were read, null when they weren't. */
    public Boolean hasWorkflow(String module, String name) {
        List<String> l = workflows.get(ImplSpec.low(module));
        if (l == null) return null;
        for (String n : l) if (ImplSpec.low(n).equals(ImplSpec.low(ImplRules.ruleName(name)))) return true;
        return false;
    }

    /** The live field matching a spec field by api name or label, or null. */
    public JsonNode findField(JsonNode specField) {
        List<JsonNode> list = fields.get(ImplSpec.low(specField.path("module").asText("")));
        if (list == null) return null;
        String api = ImplSpec.low(specField.path("api_name").asText(""));
        String label = ImplSpec.low(specField.path("label").asText(""));
        for (JsonNode f : list) {
            if (ImplSpec.low(f.path("api_name").asText()).equals(api)
                    || ImplSpec.low(f.path("field_label").asText()).equals(label)) return f;
        }
        return null;
    }

    /** True when fields of this module were read (so "not found" means missing, not unknown). */
    public boolean knowsFieldsOf(String module) {
        return fields.containsKey(ImplSpec.low(module));
    }

    public boolean hasRole(String name) {
        for (String r : roles) if (ImplSpec.low(r).equals(ImplSpec.low(name))) return true;
        return false;
    }

    public Boolean hasLayout(String module, String name) {
        List<String> l = layouts.get(ImplSpec.low(module));
        if (l == null) return null;
        for (String n : l) if (ImplSpec.low(n).equals(ImplSpec.low(name))) return true;
        return false;
    }

    /** Compact "existing modules + fields" text for prompts. */
    public String promptText(Collection<String> moduleNames, int maxChars) {
        StringBuilder sb = new StringBuilder("LIVE org metadata (connected org");
        if (!orgName.isEmpty()) sb.append(" \"").append(orgName).append('"');
        sb.append("):\nExisting modules (custom and standard; system modules are left out and must not be proposed): ");
        List<String> names = new ArrayList<>();
        for (JsonNode m : new java.util.LinkedHashSet<>(modules.values())) {
            if (!isBusinessModule(m)) continue;
            names.add(m.path("api_name").asText(""));
            if (names.size() >= 60) break;
        }
        sb.append(String.join(", ", names)).append('\n');
        if (!roles.isEmpty()) sb.append("Existing roles: ").append(String.join(", ", roles)).append('\n');
        for (String name : moduleNames) {
            List<JsonNode> list = fields.get(ImplSpec.low(name));
            if (list == null) {
                sb.append("Module ").append(name).append(": does not exist yet\n");
                continue;
            }
            sb.append("Fields of ").append(name).append(": ");
            List<String> fs = new ArrayList<>();
            for (JsonNode f : list) {
                String type = f.path("data_type").asText("");
                StringBuilder one = new StringBuilder(f.path("api_name").asText("")).append(" (").append(type);
                if (type.contains("picklist") && f.path("pick_list_values").size() > 0) {
                    List<String> opts = new ArrayList<>();
                    for (JsonNode pv : f.path("pick_list_values")) {
                        opts.add(pv.path("actual_value").asText(pv.path("display_value").asText("")));
                        if (opts.size() >= 12) { opts.add("…"); break; }
                    }
                    one.append(": ").append(String.join(" | ", opts));
                }
                fs.add(one.append(')').toString());
            }
            sb.append(String.join(", ", fs)).append('\n');
            List<String> tpls = emailTemplates.get(ImplSpec.low(moduleApiName(name) == null ? name : moduleApiName(name)));
            if (tpls != null) sb.append("Email templates of ").append(name).append(tpls.isEmpty() ? ": none (an email alert can't be built until one is made in Setup)" : ": " + String.join(" | ", tpls.subList(0, Math.min(tpls.size(), 20)))).append('\n');
            if (sb.length() > maxChars) break;
        }
        String s = sb.toString().trim();
        return s.length() > maxChars ? s.substring(0, maxChars) + "…" : s;
    }

    /** Modules a BRD can legitimately target: API-supported, not system/linking/subform generated. */
    public static boolean isBusinessModule(JsonNode m) {
        if (m == null) return false;
        if (m.has("api_supported") && !m.path("api_supported").asBoolean(true)) return false;
        String gen = m.path("generated_type").asText("").toLowerCase(java.util.Locale.ROOT);
        if (gen.equals("system") || gen.equals("linking") || gen.equals("subform")) return false;
        String api = m.path("api_name").asText("");
        return !SYSTEM_MODULES.contains(api);
    }
    private static final java.util.Set<String> SYSTEM_MODULES = new java.util.HashSet<>(java.util.Arrays.asList(
            "Actions_Performed", "Approvals", "Attachments", "Notes", "Activities", "Feeds", "Home", "Reports",
            "Dashboards", "Forecasts", "Social", "Documents", "Visits", "Analytics", "Email_Templates",
            "Email_Analytics", "Users", "Territories", "Locking_Information__s", "Linking_Module__s"));

    private static JsonNode get(CrmClient crm, String path) throws Exception {
        CrmClient.Response r = crm.call("GET", path, null);
        if (r.statusCode == 204) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        if (r.statusCode >= 400 || r.json == null) {
            String body = r.rawBody == null ? "" : r.rawBody;
            throw new IllegalStateException("HTTP " + r.statusCode
                    + (body.isEmpty() ? "" : ": " + (body.length() > 160 ? body.substring(0, 160) + "…" : body)));
        }
        return r.json;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; }
    }
}
