package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deluge functions through the CRM v8 functions API (POST /settings/functions, multipart with a "metadata"
 * JSON part). A Deluge function created with its {@code _code} is published at once, so one call does it.
 *
 * The spec carries the body the agent wrote ({@code code}), its {@code arguments} and a {@code trigger}
 * in words; this class turns them into what Zoho wants: a category, a return type and the full definition
 *
 *   &lt;return&gt; &lt;category&gt;.&lt;name&gt;(&lt;type&gt; arg, …) { body }
 *
 * where the declaration is the category in lower case, a dot, and the name in lower case with underscores.
 */
final class ImplFunctions {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern DECLARED = Pattern.compile("^\\s*(void|string|map|int|decimal|boolean|list)\\s+[a-z_]+\\.[a-z0-9_]+\\s*\\(", Pattern.CASE_INSENSITIVE);

    private ImplFunctions() {}

    /** Zoho category from the spec's trigger wording; Automation unless it clearly says otherwise. */
    static String category(JsonNode fn) {
        String t = (fn.path("trigger").asText("") + " " + fn.path("purpose").asText("")).toLowerCase(Locale.ROOT);
        if (t.contains("button")) return "Button";
        if (t.contains("schedule") || t.contains("every day") || t.contains("daily") || t.contains("nightly") || t.contains("weekly")) return "Schedule";
        if (t.contains("related list")) return "Related List";
        if (t.contains("validation")) return "Validation Rule";
        if (t.contains("standalone") || t.contains("rest api") || t.contains("called from")) return "Standalone";
        return "Automation";
    }

    /** Zoho's default return type per category. */
    static String returnType(String category) {
        switch (category) {
            case "Button": case "Standalone": case "Related List": return "string";
            case "Validation Rule": return "map";
            default: return "void";
        }
    }

    /** API name: letters, digits, _ and -, starts with a letter, 3–100 chars. */
    static String apiName(JsonNode fn) {
        String n = fn.path("name").asText("").replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("_+", "_");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) n = "fn_" + n;
        if (n.length() < 3) n = n + "_fn";
        return n.length() > 100 ? n.substring(0, 100) : n;
    }

    /** The name used in the declaration: lower case, spaces → underscores. */
    static String delugeName(JsonNode fn) {
        return apiName(fn).toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /** Deluge argument type from the agent's wording. */
    static String argType(String t) {
        String s = t == null ? "" : t.toLowerCase(Locale.ROOT).trim();
        if (s.matches("int|integer|long|bigint|number|id|record_id|recordid")) return "int";
        if (s.matches("decimal|double|float|currency|percent")) return "decimal";
        if (s.matches("bool|boolean")) return "boolean";
        if (s.equals("date")) return "date";
        if (s.matches("datetime|date_time|timestamp")) return "datetime";
        if (s.matches("list|array|collection")) return "list";
        if (s.matches("map|object|json")) return "map";
        return "string";
    }

    /** Arguments as "int leadId, string note"; an Automation function with none gets the record id. */
    static String argumentList(JsonNode fn, String category) {
        List<String> parts = new ArrayList<>();
        for (JsonNode a : fn.path("arguments")) {
            String name = a.isObject() ? a.path("name").asText("") : a.asText("");
            if (name.isEmpty()) continue;
            parts.add(argType(a.isObject() ? a.path("type").asText("") : "") + " " + name.replaceAll("[^A-Za-z0-9_]", "_"));
        }
        if (parts.isEmpty() && ("Automation".equals(category) || "Button".equals(category))) {
            parts.add("int " + recordIdName(fn));
        }
        return String.join(", ", parts);
    }

    /** "leadId" for Leads, "dealId" for Deals, "recordId" otherwise — matches what the agent's code usually reads. */
    static String recordIdName(JsonNode fn) {
        String m = fn.path("module").asText("");
        if (m.isEmpty()) return "recordId";
        String one = m.endsWith("s") ? m.substring(0, m.length() - 1) : m;
        return one.substring(0, 1).toLowerCase(Locale.ROOT) + one.substring(1).replaceAll("[^A-Za-z0-9]", "") + "Id";
    }

    /** The complete Deluge definition Zoho stores in {@code _code}. A body that already declares itself is kept. */
    static String definition(JsonNode fn) {
        String body = fn.path("code").asText("").replace("\r\n", "\n").trim();
        if (DECLARED.matcher(body).find()) return body;
        String category = category(fn);
        String head = returnType(category) + " " + category.toLowerCase(Locale.ROOT).replace(' ', '_') + "." + delugeName(fn)
                + "(" + argumentList(fn, category) + ")";
        return head + "\n{\n" + indent(body) + "\n}";
    }

    /** The "metadata" part of the create call: {"functions":[{name, api_name, description, runtime, category, _code}]}. */
    static ObjectNode metadata(JsonNode fn) {
        ObjectNode f = MAPPER.createObjectNode();
        f.put("name", apiName(fn));
        f.put("api_name", apiName(fn));
        String desc = fn.path("purpose").asText("");
        if (!desc.isEmpty()) f.put("description", desc.length() > 3000 ? desc.substring(0, 3000) : desc);
        f.put("runtime", "Deluge 1.0");
        f.put("category", category(fn));
        f.put("_code", definition(fn));
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("functions").add(f);
        return payload;
    }

    private static String indent(String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) sb.append(line.isEmpty() ? "" : "\t" + line).append('\n');
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
    }
}
