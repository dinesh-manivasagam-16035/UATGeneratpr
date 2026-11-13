package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reads a Zoho Creator environment into the neutral {@link ImplOrg} the pipeline compares against:
 * applications and their forms as "modules", each form's fields as fields.
 *
 * Creator's public API (v2.1) exposes metadata read-only — there is no create/update endpoint for a
 * form, a field, a report, a page or a Deluge workflow — so every build item is runner work; what the
 * org read gives us is the comparison ("this form already has that field") and the agent's grounding.
 */
final class CreatorOrg {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CreatorOrg() {}

    /** "dinesh@zohocorp.com" → "dinesh" — Creator's account owner name. */
    static String owner(String email) {
        if (email == null || email.isEmpty()) return "";
        int at = email.indexOf('@');
        return (at > 0 ? email.substring(0, at) : email).replaceAll("[^A-Za-z0-9_]", "");
    }

    /** The Creator API base for this data centre, e.g. https://creator.zoho.in/api/v2.1 */
    static String base() { return CreatorModulesServlet.creatorBase(); }

    /**
     * Apps → forms → fields. {@code formNames} limits which forms' fields are read (the modules in
     * scope); when empty, every form of every app is listed but no fields are read.
     */
    static ImplOrg read(CrmClient crm, Collection<String> formNames, boolean withFields) {
        ImplOrg o = new ImplOrg();
        if (crm == null) { o.errors.add("creator: not connected"); return o; }
        CrmClient cc = crm;
        String owner = owner(Products.owner());
        if (owner.isEmpty()) {
            o.errors.add("creator: the account owner name isn't known — send creator_owner with the request or set CREATOR_OWNER.");
            return o;
        }
        o.orgName = owner.isEmpty() ? "Zoho Creator" : owner;
        o.sandbox = true;                  // Creator has development/stage environments, not sandbox orgs
        List<String[]> forms = new ArrayList<>();      // {appLink, formLink, formName}
        try {
            JsonNode apps = get(cc, base() + "/" + owner + "/apps").path("applications");
            if (!apps.isArray()) apps = get(cc, base() + "/" + owner + "/apps").path("data");
            for (JsonNode app : apps) {
                String appLink = app.path("link_name").asText(app.path("app_link_name").asText(""));
                if (appLink.isEmpty()) continue;
                try {
                    JsonNode fs = get(cc, base() + "/" + owner + "/" + appLink + "/form").path("forms");
                    if (!fs.isArray()) fs = get(cc, base() + "/" + owner + "/" + appLink + "/form").path("data");
                    for (JsonNode f : fs) {
                        String link = f.path("link_name").asText(f.path("form_link_name").asText(""));
                        if (link.isEmpty()) continue;
                        String name = f.path("display_name").asText(f.path("form_name").asText(link));
                        forms.add(new String[]{appLink, link, name});
                        ObjectNode m = MAPPER.createObjectNode();
                        m.put("api_name", link);
                        m.put("singular_label", name);
                        m.put("plural_label", name);
                        m.put("id", appLink + "/" + link);
                        m.put("generated_type", "custom");
                        for (String k : new String[]{link, name}) o.modules.put(ImplSpec.low(k), m);
                    }
                } catch (Exception e) {
                    o.errors.add("forms of " + appLink + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            o.errors.add("applications: " + e.getMessage());
        }
        if (withFields) {
            for (String[] f : forms) {
                boolean wanted = formNames == null || formNames.isEmpty();
                if (!wanted) for (String n : formNames) if (ImplSpec.low(n).equals(ImplSpec.low(f[1])) || ImplSpec.low(n).equals(ImplSpec.low(f[2]))) wanted = true;
                if (!wanted) continue;
                try {
                    List<JsonNode> list = new ArrayList<>();
                    for (JsonNode fl : get(cc, base() + "/meta/" + owner + "/" + f[0] + "/form/" + f[1] + "/fields").path("fields")) {
                        list.add(toCrmField(fl));
                    }
                    o.fields.put(ImplSpec.low(f[1]), list);
                    if (!ImplSpec.low(f[1]).equals(ImplSpec.low(f[2]))) o.fields.put(ImplSpec.low(f[2]), list);
                } catch (Exception e) {
                    o.errors.add("fields of " + f[1] + ": " + e.getMessage());
                }
            }
        }
        return o;
    }

    /** A Creator field as the pipeline's neutral field shape. */
    static ObjectNode toCrmField(JsonNode f) {
        ObjectNode o = MAPPER.createObjectNode();
        String link = f.path("link_name").asText(f.path("field_link_name").asText(""));
        o.put("api_name", link);
        o.put("field_label", f.path("display_name").asText(f.path("field_name").asText(link)));
        o.put("id", link);
        o.put("data_type", dataType(f.path("type").asText(f.path("field_type").asText(""))));
        o.put("custom_field", true);
        if (f.path("choices").isArray()) {
            for (JsonNode c : f.path("choices")) {
                String v = c.isObject() ? c.path("value").asText(c.path("display_value").asText("")) : c.asText("");
                if (!v.isEmpty()) o.withArray("pick_list_values").addObject().put("actual_value", v).put("display_value", v);
            }
        }
        return o;
    }

    /** Creator field type → the pipeline's CRM-ish type name. */
    static String dataType(String t) {
        String s = t == null ? "" : t.toLowerCase(java.util.Locale.ROOT).replace(" ", "_").replace("-", "_");
        if (s.contains("email")) return "email";
        if (s.contains("phone")) return "phone";
        if (s.contains("url") || s.contains("website")) return "website";
        if (s.contains("multi") && s.contains("select")) return "multiselectpicklist";
        if (s.contains("dropdown") || s.contains("radio") || s.contains("picklist")) return "picklist";
        if (s.contains("date_time") || s.contains("datetime")) return "datetime";
        if (s.startsWith("date")) return "date";
        if (s.contains("decimal") || s.contains("percent")) return "decimal";
        if (s.contains("currency")) return "currency";
        if (s.contains("number") || s.contains("integer")) return "integer";
        if (s.contains("checkbox") || s.contains("boolean")) return "boolean";
        if (s.contains("lookup") || s.contains("subform")) return "lookup";
        if (s.contains("textarea") || s.contains("multi_line")) return "textarea";
        return "text";
    }

    private static JsonNode get(CrmClient cc, String path) throws Exception {
        CrmClient.Response res = cc.call("GET", path, null);
        if (res.statusCode >= 400) throw new IllegalStateException("HTTP " + res.statusCode + " on " + path);
        return res.json == null ? MAPPER.createObjectNode() : res.json;
    }
}
