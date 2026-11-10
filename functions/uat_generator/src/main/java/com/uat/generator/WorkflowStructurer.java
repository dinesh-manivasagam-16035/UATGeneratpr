package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a workflow item's structured form (trigger_type, trigger_fields, conditions,
 * field_updates, tasks) from its plain-language trigger / criteria / actions when the agent
 * left them empty. Plain code, no model call: the phrases are regular enough
 * ("On create", "When PIDG Code is empty", "Set Lead Status to PIDG Pending") and a wrong
 * guess shows up in the dry run before anything is built.
 *
 * Field names are left as labels when they can't be resolved; the apply step accepts labels
 * (ImplOrg.field matches api name or label), so "Lead Status" still finds Lead_Status.
 */
public final class WorkflowStructurer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowStructurer() {}

    /** Structures every "workflow"-type item of the spec that lacks a structured form. */
    public static void structureAll(ObjectNode spec, ImplOrg org) {
        for (JsonNode n : spec.path("workflows")) {
            ObjectNode wf = (ObjectNode) n;
            if (!"workflow".equals(wf.path("type").asText(""))) continue;
            structure(wf, spec, org);
        }
    }

    /** Fills what is missing; never overwrites a structured value the agent did provide. */
    public static void structure(ObjectNode wf, JsonNode spec, ImplOrg org) {
        String module = wf.path("module").asText("");
        Map<String, String> labels = labelsToApi(module, spec, org);

        if (wf.path("trigger_type").asText("").isEmpty()) {
            String[] t = trigger(wf.path("trigger").asText(""), labels);
            if (t != null) {
                wf.put("trigger_type", t[0]);
                if (t.length > 1 && wf.path("trigger_fields").size() == 0) {
                    ArrayNode tf = wf.putArray("trigger_fields");
                    for (int i = 1; i < t.length; i++) tf.add(t[i]);
                }
            }
        }
        if (wf.path("conditions").size() == 0) {
            String crit = wf.path("criteria").asText("");
            List<ObjectNode> conds = conditions(crit, labels);
            if (!conds.isEmpty()) {
                ArrayNode arr = wf.putArray("conditions");
                conds.forEach(arr::add);
                if (!wf.hasNonNull("match") || wf.path("match").asText("").isEmpty()) {
                    wf.put("match", crit.toLowerCase(Locale.ROOT).matches(".*\\b(or|either)\\b.*") ? "or" : "and");
                }
            }
        }
        if (wf.path("field_updates").size() == 0 && wf.path("tasks").size() == 0) {
            ArrayNode fus = MAPPER.createArrayNode();
            ArrayNode tasks = MAPPER.createArrayNode();
            ArrayNode other = MAPPER.createArrayNode();
            for (JsonNode a : wf.path("actions")) {
                String text = a.asText("");
                ObjectNode fu = fieldUpdate(text, labels);
                if (fu != null) { fus.add(fu); continue; }
                ObjectNode task = task(text);
                if (task != null) { tasks.add(task); continue; }
                other.add(text);
            }
            if (fus.size() > 0) wf.set("field_updates", fus);
            if (tasks.size() > 0) wf.set("tasks", tasks);
            if (fus.size() + tasks.size() > 0 && wf.path("other_actions").size() == 0 && other.size() > 0) {
                wf.set("other_actions", other);
            }
        }
    }

    // ---- trigger ----

    /** {trigger_type[, trigger field...]} or null when the phrase isn't recognised. */
    static String[] trigger(String phrase, Map<String, String> labels) {
        String p = norm(phrase);
        if (p.isEmpty()) return null;
        boolean create = p.matches(".*\\b(creat\\w*|new record|new lead|added|insert\\w*)\\b.*");
        boolean edit = p.matches(".*\\b(edit\\w*|updat\\w*|modif\\w*|chang\\w*|save\\w*)\\b.*");
        if (p.matches(".*\\b(delet\\w*|remov\\w*)\\b.*")) return new String[]{"delete"};
        if (p.matches(".*\\b(daily|weekly|monthly|every|schedul\\w*|at \\d|days? (before|after)|date)\\b.*")) {
            return new String[]{"date_or_datetime"};
        }
        // "On edit of Stage", "when Stage changes", "Stage is updated to Closed Won"
        Matcher m = Pattern.compile("(?:edit|update|change|modif\\w*)s?\\s+(?:of|to|in|on)\\s+(?:the\\s+)?([a-z0-9 _]+?)(?=\\s+(?:field|to|is|becomes|changes|from)\\b|$|[,.;])").matcher(p);
        boolean found = m.find();
        if (!found) {
            m = Pattern.compile("(?:when|if|once)\\s+(?:the\\s+)?([a-z0-9 _]+?)\\s+(?:is\\s+)?(?:changes?|changed|updated|edited|becomes|is set|set to)\\b").matcher(p);
            found = m.find();
        }
        if (found) {
            String field = resolve(m.group(1), labels);
            if (field != null && !create) return new String[]{"field_update", field};
        }
        if (create && edit) return new String[]{"create_or_edit"};
        if (create) return new String[]{"create"};
        if (edit) return new String[]{"edit"};
        return null;
    }

    // ---- conditions ----

    private static final Pattern COND = Pattern.compile(
            "(?:^|\\b(?:when|if|where|and|or)\\b)\\s*(?:the\\s+)?([a-z0-9 _%#]+?)\\s+"
            + "(is not empty|is not blank|is filled|has a value|is empty|is blank|is missing|is not provided|is not set|is null|"
            + "does not contain|doesn't contain|not contains?|contains?|starts? with|begins? with|ends? with|"
            + "is not equal to|is not|not equals?|!=|<>|is greater than or equal to|>=|is less than or equal to|<=|"
            + "is greater than|is more than|is above|exceeds|>|is less than|is below|is under|<|"
            + "is equal to|equals?|is set to|is|=)"
            + "\\s*(?:to\\s+)?(?:\"([^\"]*)\"|'([^']*)'|([^,;]+?))?(?=\\s*(?:,|;|\\band\\b|\\bor\\b|$))");

    static List<ObjectNode> conditions(String criteria, Map<String, String> labels) {
        List<ObjectNode> out = new ArrayList<>();
        String orig = criteria == null ? "" : criteria.trim().replaceAll("\\s+", " ");
        String c = orig.toLowerCase(Locale.ROOT);
        if (c.isEmpty() || c.matches("(all records|always|any record|every record|none|no criteria|n/a|-)\\.?")) return out;
        Matcher m = COND.matcher(c);
        while (m.find()) {
            String field = resolve(m.group(1), labels);
            if (field == null) continue;
            String op = m.group(2);
            int g = m.group(3) != null ? 3 : m.group(4) != null ? 4 : m.group(5) != null ? 5 : 0;
            String value = g == 0 ? "" : orig.substring(m.start(g), m.end(g)).trim().replaceAll("[.]$", "");
            String lowValue = value.toLowerCase(Locale.ROOT);
            String cmp = comparator(op);
            if (cmp == null) continue;
            if (("is_empty".equals(cmp) || "is_not_empty".equals(cmp))) value = "";
            else if (value.isEmpty()) continue;
            // "is empty" written as "X is empty" matches "is" + value "empty": catch it.
            if ("equal".equals(cmp) && lowValue.matches("(empty|blank|null|missing|not provided|not set)")) { cmp = "is_empty"; value = ""; }
            if ("not_equal".equals(cmp) && lowValue.matches("(empty|blank|null|missing)")) { cmp = "is_not_empty"; value = ""; }
            out.add(MAPPER.createObjectNode().put("field", field).put("comparator", cmp).put("value", value));
        }
        return out;
    }

    private static String comparator(String op) {
        switch (op) {
            case "is not empty": case "is not blank": case "is filled": case "has a value": return "is_not_empty";
            case "is empty": case "is blank": case "is missing": case "is not provided": case "is not set": case "is null": return "is_empty";
            case "does not contain": case "doesn't contain": case "not contain": case "not contains": return "not_contains";
            case "contain": case "contains": return "contains";
            case "start with": case "starts with": case "begin with": case "begins with": return "starts_with";
            case "end with": case "ends with": return "ends_with";
            case "is not equal to": case "is not": case "not equal": case "not equals": case "!=": case "<>": return "not_equal";
            case "is greater than or equal to": case ">=": return "greater_equal";
            case "is less than or equal to": case "<=": return "less_equal";
            case "is greater than": case "is more than": case "is above": case "exceeds": case ">": return "greater_than";
            case "is less than": case "is below": case "is under": case "<": return "less_than";
            case "is equal to": case "equal": case "equals": case "is set to": case "is": case "=": return "equal";
            default: return null;
        }
    }

    // ---- actions ----

    private static final Pattern SET = Pattern.compile(
            "^(?:set|update|change|mark|fill|populate|assign)\\s+(?:the\\s+)?([a-z0-9 _%#]+?)\\s+(?:to|as|=|with)\\s+(?:\"([^\"]*)\"|'([^']*)'|(.+?))\\.?$");

    static ObjectNode fieldUpdate(String action, Map<String, String> labels) {
        String a = norm(action);
        Matcher m = SET.matcher(a);
        if (!m.find()) return null;
        String field = resolve(m.group(1), labels);
        if (field == null) return null;
        String value = firstNonNull(m.group(2), m.group(3), m.group(4));
        if (value == null) return null;
        // Keep the value's original casing from the action text.
        int at = action.toLowerCase(Locale.ROOT).lastIndexOf(value.toLowerCase(Locale.ROOT));
        if (at >= 0) value = action.substring(at, at + value.length()).trim().replaceAll("^[\"']|[\"'.]$", "");
        return MAPPER.createObjectNode().put("field", field).put("value", value);
    }

    private static final Pattern TASK = Pattern.compile(
            "(?:create|add|assign|open)\\s+(?:a\\s+)?task\\b(?:[^\"']*?(?:\"([^\"]+)\"|'([^']+)'|(?:subject|titled|called|named)\\s+([^,;]+?)))?(?=,|;|\\s+(?:for|to|due|with|priority)\\b|$)");

    static ObjectNode task(String action) {
        String a = norm(action);
        Matcher m = TASK.matcher(a);
        if (!m.find()) return null;
        String subject = firstNonNull(m.group(1), m.group(2), m.group(3));
        if (subject == null) {
            // "Create a task to complete PIDG" → subject from the rest of the sentence
            Matcher r = Pattern.compile("task\\s+(?:to|for)\\s+([^,;]+)").matcher(a);
            subject = r.find() ? r.group(1).trim() : "Follow up";
        }
        // Original casing for the subject.
        int at = action.toLowerCase(Locale.ROOT).indexOf(subject.toLowerCase(Locale.ROOT));
        if (at >= 0) subject = action.substring(at, at + subject.length());
        subject = subject.trim().replaceAll("[\"'.]+$", "");
        ObjectNode t = MAPPER.createObjectNode().put("subject", subject);
        Matcher d = Pattern.compile("(?:due|within|in)\\s+(\\d+)\\s*(?:business\\s+|working\\s+)?days?").matcher(a);
        t.put("due_in_days", d.find() ? Integer.parseInt(d.group(1)) : 1);
        Matcher p = Pattern.compile("\\b(highest|high|normal|low|lowest)\\b(?:\\s+priority)?|priority\\s*[:=]?\\s*(highest|high|normal|low|lowest)").matcher(a);
        String pr = p.find() ? firstNonNull(p.group(1), p.group(2)) : "normal";
        t.put("priority", Character.toUpperCase(pr.charAt(0)) + pr.substring(1));
        return t;
    }

    // ---- field name resolution ----

    /** label (lower-case) → api name, from the live org and the spec's own new fields. */
    static Map<String, String> labelsToApi(String module, JsonNode spec, ImplOrg org) {
        Map<String, String> m = new LinkedHashMap<>();
        if (org != null) {
            List<JsonNode> live = org.fields.get(ImplSpec.low(module));
            if (live != null) for (JsonNode f : live) {
                String api = f.path("api_name").asText("");
                if (api.isEmpty()) continue;
                m.put(ImplSpec.low(f.path("field_label").asText()), api);
                m.put(ImplSpec.low(api), api);
            }
        }
        for (JsonNode f : spec.path("fields")) {
            if (!ImplSpec.low(f.path("module").asText()).equals(ImplSpec.low(module))) continue;
            String api = f.path("api_name").asText("");
            if (api.isEmpty()) api = ImplSpec.apiName(f.path("label").asText());
            m.putIfAbsent(ImplSpec.low(f.path("label").asText()), api);
            m.putIfAbsent(ImplSpec.low(api), api);
        }
        // Standard fields the agent names most often, when the org wasn't read.
        for (String std : new String[]{"Lead_Status", "Lead_Source", "Rating", "Stage", "Amount", "Owner", "Email",
                                       "Phone", "Company", "Industry", "Country", "State", "City", "Description",
                                       "Closing_Date", "Probability", "Type", "Priority", "Status"}) {
            m.putIfAbsent(ImplSpec.low(std), std);
        }
        return m;
    }

    /** The field's api name, or the label itself when it looks like a field name but is unknown. */
    private static String resolve(String label, Map<String, String> labels) {
        String k = ImplSpec.low(label.trim().replaceAll("^(?:(?:when|if|where|once|and|or|the|a|an)\\s+)+", "").replaceAll("\\s+field$", ""));
        if (k.isEmpty()) return null;
        if (labels.containsKey(k)) return labels.get(k);
        // Unknown but field-like (1-4 words, no verbs): keep the label; apply matches labels too.
        if (k.matches("[a-z0-9_%#]+( [a-z0-9_%#]+){0,3}") && !k.matches(".*\\b(record|lead|deal|contact|account|it|this|that)\\b.*")) {
            return label.trim().replaceAll("^(?i)(?:(?:when|if|where|once|and|or|the|a|an)\\s+)+", "").replaceAll("\\s+field$", "");
        }
        return null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String firstNonNull(String... v) {
        for (String s : v) if (s != null) return s;
        return null;
    }
}
