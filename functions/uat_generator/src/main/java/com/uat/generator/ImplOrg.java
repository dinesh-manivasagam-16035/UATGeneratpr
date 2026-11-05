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
 * A section that can't be read is left empty and listed in errors.
 */
public final class ImplOrg {

    public String orgId = "";
    public String orgName = "";
    public String orgType = "";
    public boolean sandbox;
    /** lower-cased api_name / singular / plural label → module JSON */
    public final Map<String, JsonNode> modules = new LinkedHashMap<>();
    /** module api_name (as asked) → fields JSON */
    public final Map<String, List<JsonNode>> fields = new LinkedHashMap<>();
    public final Map<String, List<String>> layouts = new LinkedHashMap<>();
    public final List<String> roles = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();

    private ImplOrg() {}

    public static ImplOrg read(CrmClient crm, Collection<String> moduleNames, boolean withLayouts) {
        ImplOrg o = new ImplOrg();
        try {
            JsonNode org = get(crm, "/crm/v3/org").path("org").path(0);
            o.orgId = org.path("id").asText("");
            o.orgName = org.path("company_name").asText("");
            o.orgType = org.path("type").asText("");
        } catch (Exception e) {
            o.errors.add("org: " + e.getMessage());
        }
        String t = o.orgType.toLowerCase(Locale.ROOT);
        o.sandbox = t.contains("sandbox") || t.contains("developer")
                || crm.apiBase().toLowerCase(Locale.ROOT).contains("sandbox");

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
        return o;
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
        sb.append("):\nExisting modules: ");
        List<String> names = new ArrayList<>();
        for (JsonNode m : new java.util.LinkedHashSet<>(modules.values())) {
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
                fs.add(f.path("api_name").asText("") + " (" + f.path("data_type").asText("") + ")");
            }
            sb.append(String.join(", ", fs)).append('\n');
            if (sb.length() > maxChars) break;
        }
        String s = sb.toString().trim();
        return s.length() > maxChars ? s.substring(0, maxChars) + "…" : s;
    }

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
