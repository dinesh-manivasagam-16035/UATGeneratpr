package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Email alert actions through POST settings/automation/email_notifications (CRM v8). The alert needs an
 * existing template (templates have no create API) and recipients; the spec names recipients in words
 * ("record owner", "owner's manager", a role, an email field, an address) and this class turns each into
 * Zoho's recipient object: merge_field tokens like ${!Leads.Owner}, roles by id, or literal emails.
 */
final class ImplEmailAlerts {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplEmailAlerts() {}

    /** The template of this module whose name matches (case-insensitive), or null. */
    static JsonNode template(JsonNode templates, String name) {
        if (templates == null || name == null || name.trim().isEmpty()) return null;
        String want = name.trim();
        for (JsonNode t : templates.path("email_templates")) if (t.path("name").asText("").equalsIgnoreCase(want)) return t;
        for (JsonNode t : templates.path("email_templates")) if (t.path("name").asText("").toLowerCase(Locale.ROOT).contains(want.toLowerCase(Locale.ROOT))) return t;
        return null;
    }

    /**
     * One recipient. Returns null when the words match nothing; the caller lists those as a follow-up.
     * @param roleIds lower-cased role name → id
     * @param fieldOf live field by api name or label (null when unknown)
     */
    static ObjectNode recipient(String text, String moduleApi, Map<String, String> roleIds, Function<String, JsonNode> fieldOf) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return null;
        String low = t.toLowerCase(Locale.ROOT);
        if (t.contains("@") && !t.startsWith("$")) {
            ObjectNode r = MAPPER.createObjectNode().put("type", "emails");
            r.putObject("details").putArray("emails").add(t);
            return r;
        }
        if (low.matches("(record )?owner('s)? manager|owner's reporting manager|reporting manager|manager of (the )?(record )?owner")) return merge("${!" + moduleApi + ".Owner.Reporting_To}");
        if (low.matches("(record )?owner|assigned (user|to)|assignee")) return merge("${!" + moduleApi + ".Owner}");
        if (low.matches("creator|created by|record creator")) return merge("${!" + moduleApi + ".Created_By}");
        if (roleIds != null) {
            String id = roleIds.get(low.replaceAll("^role[: ]+", ""));
            if (id != null) { ObjectNode r = MAPPER.createObjectNode().put("type", "role"); r.putObject("resource").put("name", t.replaceAll("(?i)^role[: ]+", "")).put("id", id); return r; }
        }
        if (fieldOf != null) {
            JsonNode f = fieldOf.apply(t.replaceAll("(?i)\\s*(field|'s email)$", ""));
            if (f != null) {
                String dt = f.path("data_type").asText("");
                String api = f.path("api_name").asText();
                if (dt.equals("email") || dt.equals("userlookup") || dt.equals("ownerlookup") || dt.equals("multiuserlookup")) return merge("${!" + moduleApi + "." + api + "}");
                if (dt.equals("lookup")) return merge("${!" + moduleApi + "." + api + ".Email}");
            }
        }
        return null;
    }

    private static ObjectNode merge(String token) {
        ObjectNode r = MAPPER.createObjectNode().put("type", "merge_field");
        r.putObject("details").put("api_name", token);
        return r;
    }

    /** {"email_notifications":[{name, module, template, recipients:{to:[…]}, feature_type}]} */
    static ObjectNode body(String name, String moduleApi, String moduleId, JsonNode template, ArrayNode to) {
        ObjectNode a = MAPPER.createObjectNode();
        a.put("name", safeName(name));
        a.putObject("module").put("api_name", moduleApi).put("id", moduleId);
        a.putObject("template").put("name", template.path("name").asText()).put("id", template.path("id").asText());
        a.putObject("recipients").set("to", to);
        a.put("feature_type", "workflow");
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("email_notifications").add(a);
        return payload;
    }

    /** Alert names allow no special characters: letters, digits, spaces and hyphens, ≤ 250. */
    static String safeName(String s) {
        String n = (s == null ? "" : s).replaceAll("[^A-Za-z0-9 \\-]", " ").replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) n = "Email alert";
        return n.length() > 250 ? n.substring(0, 250) : n;
    }
}
