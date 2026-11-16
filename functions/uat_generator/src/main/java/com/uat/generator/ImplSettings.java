package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Org-level settings that are one small API call each: tags on a module, CRM variables and record
 * locking. They share a spec section ("settings") with a type discriminator so the blueprint keeps
 * one row per thing the BRD asked for rather than three thin sections.
 */
final class ImplSettings {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The 13 colours the tag API accepts; anything else is dropped so the call isn't rejected. */
    private static final List<String> TAG_COLOURS = Arrays.asList(
            "#F17574", "#FF8577", "#FFA177", "#FFC777", "#F5E26B", "#B5D96F", "#7DD67D",
            "#57B1FD", "#6E8FFF", "#A98BFF", "#E58BFF", "#FF8BC6", "#969696");

    /** Variable types the API accepts. */
    private static final List<String> VAR_TYPES = Arrays.asList(
            "integer", "text", "percent", "decimal", "currency", "date", "datetime",
            "email", "phone", "url", "checkbox", "textarea", "long");

    private ImplSettings() {}

    static String tagName(String s) {
        String n = (s == null ? "" : s).replaceAll("[<>,]", " ").replaceAll("\\s+", " ").trim();
        return n.length() > 25 ? n.substring(0, 25).trim() : n;
    }

    /** {"tags":[{name, color_code?}]} — names the module doesn't have yet, in spec order. */
    static ObjectNode tags(List<String> names, String colour) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode arr = payload.putArray("tags");
        for (String n : names) {
            ObjectNode t = arr.addObject().put("name", tagName(n));
            if (colour != null && TAG_COLOURS.contains(colour)) t.put("color_code", colour);
        }
        return payload;
    }

    /** Names in {@code wanted} that the module's live tag list doesn't already have. */
    static List<String> newTags(JsonNode live, Iterable<String> wanted) {
        List<String> out = new ArrayList<>();
        for (String w : wanted) {
            String n = tagName(w);
            if (n.isEmpty()) continue;
            boolean have = false;
            if (live != null) for (JsonNode t : live.path("tags")) if (n.equalsIgnoreCase(t.path("name").asText())) have = true;
            if (!have && !out.contains(n)) out.add(n);
        }
        return out;
    }

    static String variableType(String s) {
        String t = (s == null ? "" : s).toLowerCase(Locale.ROOT).trim();
        if (t.equals("number") || t.equals("int")) t = "integer";
        if (t.equals("boolean")) t = "checkbox";
        if (t.equals("string")) t = "text";
        return VAR_TYPES.contains(t) ? t : "text";
    }

    static String apiName(String s) {
        String n = (s == null ? "" : s).replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) n = "Var_" + n;
        return n;
    }

    /** {"variables":[{name, api_name, type, value?, description?, variable_group:{id}}]} */
    static ObjectNode variable(JsonNode v, String groupId) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("name", v.path("name").asText());
        o.put("api_name", v.path("api_name").asText("").isEmpty() ? apiName(v.path("name").asText()) : apiName(v.path("api_name").asText()));
        o.put("type", variableType(v.path("data_type").asText(v.path("type").asText(""))));
        String value = v.path("value").asText("");
        if (!value.isEmpty()) o.put("value", value);
        String desc = v.path("description").asText("");
        if (!desc.isEmpty()) o.put("description", desc);
        o.putObject("variable_group").put("id", groupId);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("variables").add(o);
        return payload;
    }

    /** The group id to use: the one the spec names, else the first group the org has. */
    static String groupId(JsonNode groups, String wanted) {
        String want = wanted == null ? "" : wanted.trim();
        String first = null;
        for (JsonNode g : groups.path("variable_groups")) {
            String id = g.path("id").asText();
            if (first == null) first = id;
            if (!want.isEmpty() && (g.path("name").asText("").equalsIgnoreCase(want)
                    || g.path("api_name").asText("").equalsIgnoreCase(want)
                    || g.path("display_label").asText("").equalsIgnoreCase(want))) return id;
        }
        return first;
    }

    static boolean hasVariable(JsonNode live, String name, String apiName) {
        if (live == null) return false;
        for (JsonNode v : live.path("variables")) {
            if (v.path("name").asText("").equalsIgnoreCase(name) || v.path("api_name").asText("").equalsIgnoreCase(apiName)) return true;
        }
        return false;
    }

    /**
     * {"record_locking_configurations":[{lock_type, locking_rules:[{name, criteria{group_operator,group}}],
     * locked_for, lock_excluded_profiles, excluded_fields, restricted_actions}]}
     */
    static ObjectNode recordLocking(JsonNode r, Function<String, JsonNode> fieldOf, List<String[]> excludedProfiles) {
        ObjectNode o = MAPPER.createObjectNode();
        boolean auto = r.path("conditions").size() > 0;
        o.put("lock_type", auto ? (r.path("manual_too").asBoolean(false) ? "both" : "automatic") : "manual");
        if (auto) {
            ObjectNode rule = o.putArray("locking_rules").addObject();
            rule.put("name", r.path("name").asText("Locking rule"));
            rule.put("lock_existing_records", r.path("lock_existing").asBoolean(false));
            ObjectNode crit = rule.putObject("criteria");
            crit.put("group_operator", r.path("match").asText("and").toUpperCase(Locale.ROOT));
            ArrayNode group = crit.putArray("group");
            for (JsonNode c : r.path("conditions")) {
                JsonNode f = fieldOf == null ? null : fieldOf.apply(c.path("field").asText());
                ObjectNode one = group.addObject();
                one.put("comparator", c.path("comparator").asText("equal"));
                ObjectNode fr = one.putObject("field").put("api_name", f == null ? c.path("field").asText() : f.path("api_name").asText());
                if (f != null && f.has("id")) fr.put("id", f.path("id").asText());
                one.put("value", c.path("value").asText(""));
            }
        }
        if (excludedProfiles != null && !excludedProfiles.isEmpty()) {
            o.put("locked_for", "all_profiles_except_excluded_profiles");
            ArrayNode ex = o.putArray("lock_excluded_profiles");
            for (String[] p : excludedProfiles) ex.addObject().put("id", p[0]).put("api_name", p[1]);
        } else {
            o.put("locked_for", "all_profiles");
        }
        List<String> actions = new ArrayList<>();
        actions.add("update");
        for (JsonNode a : r.path("restricted_actions")) {
            String v = a.asText("").toLowerCase(Locale.ROOT).replace(' ', '_');
            if (Arrays.asList("tags", "convert", "delete", "change_owner").contains(v) && !actions.contains(v)) actions.add(v);
        }
        ArrayNode acts = o.putArray("restricted_actions");
        for (String a : actions) acts.add(a);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("record_locking_configurations").add(o);
        return payload;
    }
}
