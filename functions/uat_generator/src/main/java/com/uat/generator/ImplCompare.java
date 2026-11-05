package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares a blueprint item with the live org, shared by /implement (status) and
 * /implement/verify (verdict), so both agree on what "matches" means.
 *
 * Statuses set on items:
 *   new           not in the org; will be created
 *   exists        in the org and matches; nothing to do (unticked by default)
 *   needs_update  in the org, but a picklist lacks values the BRD needs; values are added (PATCH)
 *   conflict      in the org with a different type (or lookup target); CRM can't change a
 *                 field's type, so the reviewer creates a new field or skips it
 *   not_compared  the org couldn't be read for this item (or CRM isn't connected)
 */
public final class ImplCompare {

    private ImplCompare() {}

    /** Live CRM data_type in blueprint terms (double → decimal, ownerlookup → userlookup). */
    public static String liveType(String crmType) {
        String t = crmType == null ? "" : crmType.toLowerCase(java.util.Locale.ROOT);
        if ("double".equals(t)) return "decimal";
        if ("ownerlookup".equals(t)) return "userlookup";
        return t;
    }

    /** Blueprint values missing from a live picklist (compared on display and actual value). */
    public static List<String> missingPicklistValues(JsonNode spec, JsonNode live) {
        List<String> have = new ArrayList<>();
        for (JsonNode p : live.path("pick_list_values")) {
            have.add(ImplSpec.low(p.path("actual_value").asText("")));
            have.add(ImplSpec.low(p.path("display_value").asText("")));
        }
        List<String> missing = new ArrayList<>();
        for (JsonNode v : spec.path("picklist_values")) {
            if (!have.contains(ImplSpec.low(v.asText()))) missing.add(v.asText());
        }
        return missing;
    }

    /** Why a live field can't become the blueprint field without a new field, or null. */
    public static String conflict(JsonNode spec, JsonNode live) {
        String want = spec.path("data_type").asText("");
        String got = liveType(live.path("data_type").asText(""));
        if (!want.isEmpty() && !got.isEmpty() && !want.equals(got)) {
            return "Exists as " + got + "; the blueprint wants " + want + ".";
        }
        String target = spec.path("lookup_module").asText("");
        String liveTarget = live.path("lookup").path("module").path("api_name").asText("");
        if (!target.isEmpty() && !liveTarget.isEmpty() && !ImplSpec.low(target).equals(ImplSpec.low(liveTarget))) {
            return "Lookup points to " + liveTarget + "; the blueprint wants " + target + ".";
        }
        return null;
    }

    /** Sets status (and live_id / live_type / missing_values / note) on a blueprint field. */
    public static void fieldStatus(ObjectNode f, ImplOrg org) {
        f.remove("missing_values");
        String module = f.path("module").asText();
        if (org == null) { f.put("status", "not_compared"); return; }
        JsonNode live = org.findField(f);
        if (live == null) {
            boolean known = org.knowsFieldsOf(module) || (!org.modules.isEmpty() && org.moduleApiName(module) == null);
            f.put("status", known ? "new" : "not_compared");
            return;
        }
        f.put("live_id", live.path("id").asText(""));
        f.put("live_api_name", live.path("api_name").asText(""));
        f.put("live_type", liveType(live.path("data_type").asText("")));
        String why = conflict(f, live);
        if (why != null) {
            f.put("status", "conflict");
            f.put("note", why);
            return;
        }
        List<String> missing = missingPicklistValues(f, live);
        if (!missing.isEmpty()) {
            f.put("status", "needs_update");
            com.fasterxml.jackson.databind.node.ArrayNode arr = f.putArray("missing_values");
            missing.forEach(arr::add);
            f.put("note", "Add: " + String.join(", ", missing));
            return;
        }
        f.put("status", "exists");
    }
}
