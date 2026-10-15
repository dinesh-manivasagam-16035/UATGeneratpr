package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a module's real field metadata from the connected CRM org and turns it
 * into a compact schema for the prompt, so cases use the org's actual api names,
 * required fields, picklist values and lookups instead of a generic template.
 *
 * Source: GET /crm/v3/settings/fields?module={api_name}
 * Note: this covers system-mandatory fields. Layout-level "required" settings and
 * validation rules live in other APIs and are not included yet.
 */
public final class CrmMetadata {

    private static final int MAX_CHARS = 3500;
    private static final int MAX_PICKLIST_VALUES = 12;

    private CrmMetadata() {}

    /** The CRM client for the signed-in session, or null when not connected. */
    public static CrmClient sessionClient(HttpServletRequest req) {
        String sid = null;
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) if ("tp_crm_sid".equals(c.getName())) sid = c.getValue();
        }
        CrmTokenStore.TokenBundle bundle = CrmTokenStore.get(sid);
        if (bundle != null && !bundle.isExpired() && bundle.hasOAuthCredentials()) {
            return bundle.toCrmClient();
        }
        return null;
    }

    /** Compact schema text for the module, or throws with a short reason. */
    public static String describe(CrmClient client, String module) throws Exception {
        String path = "/crm/v3/settings/fields?module=" + URLEncoder.encode(module, StandardCharsets.UTF_8.name());
        CrmClient.Response r = client.call("GET", path, null);
        if (r.statusCode >= 400 || r.json == null) {
            String body = r.rawBody == null ? "" : r.rawBody;
            throw new IllegalStateException("HTTP " + r.statusCode
                    + (body.isEmpty() ? "" : ": " + (body.length() > 160 ? body.substring(0, 160) + "…" : body)));
        }
        JsonNode fields = r.json.path("fields");
        if (!fields.isArray() || fields.size() == 0) {
            throw new IllegalStateException("no fields returned");
        }

        // Most useful first: required, then custom, then picklists/lookups, then the rest.
        List<String> required = new ArrayList<>();
        List<String> custom = new ArrayList<>();
        List<String> choice = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (JsonNode f : fields) {
            if (f.path("read_only").asBoolean(false)) continue;
            if (!f.path("visible").asBoolean(true)) continue;
            String line = fieldLine(f);
            if (line == null) continue;
            String type = f.path("data_type").asText("");
            if (f.path("system_mandatory").asBoolean(false)) required.add(line);
            else if (f.path("custom_field").asBoolean(false)) custom.add(line);
            else if (type.contains("picklist") || type.contains("lookup")) choice.add(line);
            else other.add(line);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("LIVE field metadata for ").append(module)
          .append(" from the connected org (use these exact api names):\n");
        int total = required.size() + custom.size() + choice.size() + other.size();
        int written = 0;
        for (List<String> group : List.of(required, custom, choice, other)) {
            for (String line : group) {
                if (sb.length() + line.length() + 1 > MAX_CHARS) {
                    sb.append("... and ").append(total - written).append(" more fields\n");
                    return sb.toString().trim();
                }
                sb.append(line).append('\n');
                written++;
            }
        }
        return sb.toString().trim();
    }

    private static String fieldLine(JsonNode f) {
        String api = f.path("api_name").asText("");
        if (api.isEmpty()) return null;
        String type = f.path("data_type").asText("");
        StringBuilder sb = new StringBuilder("- ").append(api).append(" (").append(type.isEmpty() ? "field" : type);

        int length = f.path("length").asInt(0);
        if (length > 0 && (type.equals("text") || type.equals("textarea") || type.equals("email")
                || type.equals("phone") || type.equals("website"))) {
            sb.append(", max ").append(length);
        }
        if (f.path("system_mandatory").asBoolean(false)) sb.append(", REQUIRED");
        if (f.path("custom_field").asBoolean(false)) sb.append(", custom");
        JsonNode unique = f.path("unique");
        if (unique.isObject() && unique.size() > 0) sb.append(", unique");

        String lookup = f.path("lookup").path("module").path("api_name").asText("");
        if (!lookup.isEmpty()) sb.append(", lookup → ").append(lookup);

        JsonNode picks = f.path("pick_list_values");
        if (picks.isArray() && picks.size() > 0) {
            List<String> vals = new ArrayList<>();
            for (JsonNode p : picks) {
                String v = p.path("actual_value").asText(p.path("display_value").asText(""));
                if (!v.isEmpty() && !"-None-".equals(v)) vals.add(v);
                if (vals.size() >= MAX_PICKLIST_VALUES) break;
            }
            if (!vals.isEmpty()) {
                sb.append(": ").append(String.join(" | ", vals));
                if (picks.size() > vals.size() + 1) sb.append(" | …");
            }
        }
        sb.append(')');
        String label = f.path("field_label").asText("");
        if (!label.isEmpty() && !label.replace(" ", "_").equalsIgnoreCase(api)) {
            sb.append(" label \"").append(label).append('"');
        }
        return sb.toString();
    }
}
