package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Record visibility: territories (POST settings/territories) and data sharing rules
 * (POST settings/data_sharing/rules?module=X) in CRM v8.
 *
 * Territory criteria are per module keys — account_rule_criteria, lead_rule_criteria,
 * deal_rule_criteria — each one criterion {comparator, field{api_name,id}, value}.
 * A sharing rule is Record_Owner_Based (shared_from a role/group) or Criteria_Based (criteria group).
 */
final class ImplSharing {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplSharing() {}

    /** read_only | read_write_delete for a territory, from the spec's words. */
    static String territoryPermission(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT);
        return t.contains("delete") || t.contains("write") || t.contains("edit") || t.contains("full") ? "read_write_delete" : "read_only";
    }

    /** read | read_write | read_write_delete for a sharing rule, from the spec's words. */
    static String sharePermission(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT);
        if (t.contains("delete") || t.contains("full")) return "read_write_delete";
        if (t.contains("write") || t.contains("edit") || t.contains("modify")) return "read_write";
        return "read";
    }

    /** The territory criteria key for a module: Accounts → account_rule_criteria. */
    static String criteriaKey(String module) {
        String m = module == null ? "" : module.toLowerCase(Locale.ROOT);
        if (m.startsWith("lead")) return "lead_rule_criteria";
        if (m.startsWith("deal") || m.startsWith("potential")) return "deal_rule_criteria";
        if (m.startsWith("account")) return "account_rule_criteria";
        return "";
    }

    /** {"territories":[{name, reporting_to:{id}, permission_type, manager?, description?, *_rule_criteria}]} */
    static ObjectNode territory(JsonNode t, String parentId, String managerId, Function<String, JsonNode> fieldOf) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("name", t.path("name").asText().replaceAll("[^A-Za-z0-9 ()._/@-]", " ").replaceAll("\\s+", " ").trim());
        o.putObject("reporting_to").put("id", parentId);
        o.put("permission_type", territoryPermission(t.path("permission_type").asText("")));
        if (managerId != null && !managerId.isEmpty()) o.putObject("manager").put("id", managerId);
        String desc = t.path("description").asText("");
        if (!desc.isEmpty()) o.put("description", desc);
        for (JsonNode c : t.path("criteria")) {
            String key = criteriaKey(c.path("module").asText(""));
            if (key.isEmpty() || o.has(key)) continue;
            JsonNode f = fieldOf == null ? null : fieldOf.apply(c.path("field").asText());
            ObjectNode crit = o.putObject(key);
            crit.put("comparator", c.path("comparator").asText("equal"));
            ObjectNode fr = crit.putObject("field").put("api_name", f == null ? c.path("field").asText() : f.path("api_name").asText());
            if (f != null && f.has("id")) fr.put("id", f.path("id").asText());
            crit.put("value", c.path("value").asText(""));
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("territories").add(o);
        return payload;
    }

    /** roles | groups | all_users from the spec's words ("Sales role and subordinates", "group X", "everyone"). */
    static String audienceType(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT);
        if (t.contains("all user") || t.contains("everyone") || t.contains("all users")) return "all_users";
        if (t.contains("group")) return "groups";
        return "roles";
    }

    static boolean subordinates(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT);
        return t.contains("subordinate") || t.contains("and below") || t.contains("downwards");
    }

    /** Strips "role"/"group"/"and subordinates" so what's left is the role or group name. */
    static String audienceName(String s) {
        return (s == null ? "" : s).replaceAll("(?i)\\b(and )?subordinates?\\b", "").replaceAll("(?i)\\b(role|group)s?\\b", "")
                .replaceAll("(?i)\\band below\\b", "").replaceAll("[,:]", " ").replaceAll("\\s+", " ").trim();
    }

    /** {resource:{id,name}, type, subordinates} — null when the name resolves to nothing. */
    static ObjectNode audience(String text, Map<String, String> roleIds, Map<String, String> groupIds) {
        String type = audienceType(text);
        ObjectNode a = MAPPER.createObjectNode();
        a.put("type", type);
        a.put("subordinates", "roles".equals(type) && subordinates(text));
        if ("all_users".equals(type)) { a.putNull("resource"); return a; }
        String name = audienceName(text);
        Map<String, String> src = "groups".equals(type) ? groupIds : roleIds;
        String id = src == null ? null : src.get(ImplSpec.low(name));
        if (id == null && src != null) {       // fall back to the other kind when the words didn't say which
            Map<String, String> other = "groups".equals(type) ? roleIds : groupIds;
            if (other != null && other.get(ImplSpec.low(name)) != null) {
                id = other.get(ImplSpec.low(name));
                a.put("type", "groups".equals(type) ? "roles" : "groups");
            }
        }
        if (id == null) return null;
        a.putObject("resource").put("id", id).put("name", name);
        return a;
    }

    /** {"sharing_rules":[{name, type, shared_to, shared_from|null, criteria?, permission_type, superiors_allowed}]} */
    static ObjectNode sharingRule(JsonNode r, ObjectNode sharedTo, ObjectNode sharedFrom, ArrayNode criteriaGroup) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("name", r.path("name").asText());
        boolean criteriaBased = criteriaGroup != null && criteriaGroup.size() > 0;
        o.put("type", criteriaBased ? "Criteria_Based" : "Record_Owner_Based");
        o.set("shared_to", sharedTo);
        if (criteriaBased) {
            o.putNull("shared_from");
            ObjectNode crit = o.putObject("criteria");
            crit.put("group_operator", r.path("match").asText("and").toUpperCase(Locale.ROOT));
            crit.set("group", criteriaGroup);
        } else {
            o.set("shared_from", sharedFrom);
        }
        o.put("permission_type", sharePermission(r.path("permission_type").asText("")));
        o.put("superiors_allowed", r.path("superiors_allowed").asBoolean(true));
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("sharing_rules").add(o);
        return payload;
    }

    /** One condition of a criteria-based rule: {comparator, field:{api_name}, type:"value", value}. */
    static ObjectNode condition(JsonNode c, Function<String, JsonNode> fieldOf) {
        JsonNode f = fieldOf == null ? null : fieldOf.apply(c.path("field").asText());
        ObjectNode o = MAPPER.createObjectNode();
        o.put("comparator", c.path("comparator").asText("equal"));
        o.putObject("field").put("api_name", f == null ? c.path("field").asText() : f.path("api_name").asText());
        o.put("type", "value");
        o.put("value", c.path("value").asText(""));
        return o;
    }

    /** An existing territory or sharing rule with this name, or null. */
    static JsonNode existing(JsonNode list, String key, String name) {
        if (list == null) return null;
        for (JsonNode n : list.path(key)) if (n.path("name").asText("").equalsIgnoreCase(name)) return n;
        return null;
    }
}
