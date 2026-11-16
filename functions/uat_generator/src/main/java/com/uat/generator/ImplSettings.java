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

    private static final List<String> DAYS = Arrays.asList("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday");

    /** "mon", "Monday", "MONDAY " → "Monday"; "" when it isn't a day. */
    static String day(String s) {
        String t = (s == null ? "" : s).trim().toLowerCase(Locale.ROOT);
        for (String d : DAYS) if (d.toLowerCase(Locale.ROOT).startsWith(t) && t.length() >= 3) return d;
        return "";
    }

    /** "9:30", "09:30", "9.30", "9:30 am", "6:30 pm" → "09:30" / "18:30"; "" when it isn't a time. */
    static String time(String s) {
        String t = (s == null ? "" : s).trim().toLowerCase(Locale.ROOT).replace('.', ':').replaceAll("\\s+", " ");
        boolean pm = t.contains("pm"), am = t.contains("am");
        t = t.replace("am", "").replace("pm", "").trim();
        if (!t.matches("\\d{1,2}(:\\d{2})?")) return "";
        String[] parts = t.split(":");
        int h = Integer.parseInt(parts[0]);
        int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        if (pm && h < 12) h += 12;
        if (am && h == 12) h = 0;
        if (h > 23 || m > 59) return "";
        return String.format("%02d:%02d", h, m);
    }

    /**
     * {"business_hours":{type, business_days, week_starts_on, same_as_everyday, daily_timing|custom_timing}}
     * type is 24*7 when every day runs round the clock, 24*5 for five such days, else custom.
     */
    static ObjectNode businessHours(JsonNode b) {
        List<String> days = new ArrayList<>();
        for (JsonNode d : b.path("days")) { String x = day(d.asText("")); if (!x.isEmpty() && !days.contains(x)) days.add(x); }
        if (days.isEmpty()) days.addAll(Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday"));
        String start = time(b.path("start").asText("")), end = time(b.path("end").asText(""));
        List<JsonNode> perDay = new ArrayList<>();
        for (JsonNode d : b.path("per_day")) if (!day(d.path("day").asText("")).isEmpty()) perDay.add(d);

        ObjectNode o = MAPPER.createObjectNode();
        boolean roundTheClock = start.isEmpty() && end.isEmpty() && perDay.isEmpty();
        o.put("type", roundTheClock ? (days.size() == 7 ? "24*7" : "24*5") : "custom");
        if (!"24*7".equals(o.path("type").asText())) {
            ArrayNode arr = o.putArray("business_days");
            // 24*5 needs exactly five days; trim or pad from the working week.
            List<String> use = new ArrayList<>(days);
            if ("24*5".equals(o.path("type").asText())) {
                while (use.size() > 5) use.remove(use.size() - 1);
                for (String d : Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")) if (use.size() < 5 && !use.contains(d)) use.add(d);
            }
            for (String d : use) arr.add(d);
            days = use;
        }
        o.put("week_starts_on", day(b.path("week_starts_on").asText("")).isEmpty() ? days.get(0) : day(b.path("week_starts_on").asText("")));
        if ("custom".equals(o.path("type").asText())) {
            if (perDay.isEmpty()) {
                o.put("same_as_everyday", true);
                ArrayNode t = o.putArray("daily_timing");
                t.add(start.isEmpty() ? "09:00" : start);
                t.add(end.isEmpty() ? "18:00" : end);
            } else {
                o.put("same_as_everyday", false);
                ArrayNode ct = o.putArray("custom_timing");
                for (JsonNode d : perDay) {
                    ObjectNode one = ct.addObject().put("days", day(d.path("day").asText("")));
                    ArrayNode t = one.putArray("business_timing");
                    String ds = time(d.path("start").asText("")), de = time(d.path("end").asText(""));
                    t.add(ds.isEmpty() ? (start.isEmpty() ? "09:00" : start) : ds);
                    t.add(de.isEmpty() ? (end.isEmpty() ? "18:00" : end) : de);
                }
            }
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("business_hours", o);
        return payload;
    }

    /** {"holidays":[{name, date (YYYY-MM-DD), type}]} — entries without a usable date are dropped. */
    static ObjectNode holidays(JsonNode h) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode arr = payload.putArray("holidays");
        for (JsonNode d : h.path("holidays")) {
            String date = d.path("date").asText("").trim();
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
            String name = d.path("name").asText("").replaceAll("[~`#%&+=\\[\\]{}|;<>^,]", " ").replaceAll("\\s+", " ").trim();
            if (name.isEmpty()) continue;
            arr.addObject().put("name", name).put("date", date).put("type", "business_holiday");
        }
        return payload;
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
