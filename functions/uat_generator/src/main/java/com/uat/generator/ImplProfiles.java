package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Profiles through the CRM v8 API: POST settings/profiles/{base}/actions/clone makes a new profile with the
 * base's permissions, PUT settings/profiles/{id} flips individual permissions by id. The spec gives the
 * permissions in words ("View Leads", "No Delete on Deals", "Export", "Mass Update Contacts"); this class
 * parses them and matches them to the profile's permissions_details (module + display_label, or name).
 */
final class ImplProfiles {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NEG = Pattern.compile("^(no|not|cannot|can't|deny|denied|without|disable[d]?|remove)\\b\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile("\\b(view|read|see|create|add|edit|modify|update|delete|remove|export|import|mass\\s*update|mass\\s*delete|mass\\s*email|convert|print|send\\s*email|change\\s*owner|share|zia|dashboard|report|workflow|tag)\\b", Pattern.CASE_INSENSITIVE);

    private ImplProfiles() {}

    /** One permission as asked: the action word Zoho uses as display_label, the module (may be ""), on/off. */
    static final class Want {
        final String action, module; final boolean enabled; final String text;
        Want(String action, String module, boolean enabled, String text) { this.action = action; this.module = module; this.enabled = enabled; this.text = text; }
    }

    /** "View Leads", "no delete on Deals", "create and edit Contacts", "Export" → one Want per action. */
    static List<Want> parse(JsonNode permissions) {
        List<Want> out = new ArrayList<>();
        for (JsonNode p : permissions) {
            String raw = p.asText("").trim();
            if (raw.isEmpty()) continue;
            boolean enabled = true;
            String s = raw;
            Matcher neg = NEG.matcher(s);
            if (neg.find()) { enabled = false; s = s.substring(neg.end()); }
            List<String> actions = new ArrayList<>();
            Matcher m = ACTION.matcher(s);
            int lastEnd = 0;
            while (m.find()) { actions.add(canonical(m.group(1))); lastEnd = m.end(); }
            String module = s.substring(lastEnd).replaceAll("(?i)^\\s*(on|of|for|in|to|the|records?|module)\\b", "").replaceAll("(?i)\\b(records?|module)\\b", "").replaceAll("[^A-Za-z0-9_ ]", " ").trim();
            if (actions.isEmpty()) { out.add(new Want("", module.isEmpty() ? raw : module, enabled, raw)); continue; }
            for (String a : actions) out.add(new Want(a, module, enabled, raw));
        }
        return out;
    }

    static String canonical(String action) {
        String a = action.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        switch (a) {
            case "read": case "see": return "View";
            case "add": return "Create";
            case "modify": case "update": return "Edit";
            case "remove": return "Delete";
            case "mass update": return "Mass Update";
            case "mass delete": return "Mass Delete";
            case "mass email": return "Mass Email";
            case "send email": return "Send Email";
            case "change owner": return "Change Owner";
            default: return a.substring(0, 1).toUpperCase(Locale.ROOT) + a.substring(1);
        }
    }

    /**
     * Matches the wants to a profile's permissions_details. Returns {changes:[{id, enabled, label}], unmatched:[text]}.
     * A want with a module matches entries of that module (api name, singular or plural label); without one it
     * matches module-less entries first, then every module's entry of that action.
     */
    static ObjectNode match(List<Want> wants, JsonNode permissionsDetails, ImplOrg org) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode changes = out.putArray("changes");
        ArrayNode unmatched = out.putArray("unmatched");
        List<String> seen = new ArrayList<>();
        for (Want w : wants) {
            int before = changes.size();
            String modApi = w.module.isEmpty() ? "" : org == null || org.moduleApiName(w.module) == null ? w.module : org.moduleApiName(w.module);
            for (JsonNode pd : permissionsDetails) {
                String label = pd.path("display_label").asText(""), name = pd.path("name").asText(""), mod = pd.path("module").asText("");
                boolean actionHit = w.action.isEmpty() ? containsWord(label, w.module) || containsWord(name, w.module)
                        : label.equalsIgnoreCase(w.action) || containsWord(name, w.action.replace(' ', '_'));
                if (!actionHit) continue;
                boolean moduleHit = w.action.isEmpty() || modApi.isEmpty() ? (w.action.isEmpty() || mod.isEmpty() || mod.equals("null"))
                        : mod.equalsIgnoreCase(modApi) || mod.equalsIgnoreCase(w.module) || containsWord(name, modApi);
                if (!moduleHit) continue;
                String id = pd.path("id").asText();
                if (seen.contains(id)) continue;
                seen.add(id);
                changes.addObject().put("id", id).put("enabled", w.enabled).put("label", (label.isEmpty() ? name : label) + (mod.isEmpty() || mod.equals("null") ? "" : " " + mod));
            }
            if (changes.size() == before) unmatched.add(w.text);
        }
        return out;
    }

    private static boolean containsWord(String hay, String needle) {
        if (needle == null || needle.isEmpty()) return false;
        return hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** PUT body: {"profiles":[{"permissions_details":[{id, enabled}]}]} */
    static ObjectNode updateBody(ArrayNode changes) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode pds = payload.putArray("profiles").addObject().putArray("permissions_details");
        for (JsonNode c : changes) pds.addObject().put("id", c.path("id").asText()).put("enabled", c.path("enabled").asBoolean());
        return payload;
    }

    /** Clone body: {"profiles":[{"name", "description"}]} */
    static ObjectNode cloneBody(String name, String description) {
        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode p = payload.putArray("profiles").addObject();
        p.put("name", name.length() > 50 ? name.substring(0, 50) : name);
        if (description != null && !description.isEmpty()) p.put("description", description.length() > 250 ? description.substring(0, 250) : description);
        return payload;
    }
}
