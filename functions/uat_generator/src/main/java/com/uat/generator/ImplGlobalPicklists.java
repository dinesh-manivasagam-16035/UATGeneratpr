package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Global picklists (shared option sets) through POST settings/global_picklists (CRM v8). One set per call;
 * options carry display_value (actual_value is derived by Zoho) and their order. A picklist field is linked
 * to the set by {@code global_picklist:{id}} on field create, with no pick_list_values of its own.
 */
final class ImplGlobalPicklists {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplGlobalPicklists() {}

    /** {"global_picklists":[{display_label, description?, pick_list_values:[{display_value, sequence_number}]}]} */
    static ObjectNode payload(JsonNode g) {
        ObjectNode set = MAPPER.createObjectNode();
        String label = g.path("name").asText("");
        set.put("display_label", label.length() > 50 ? label.substring(0, 50) : label);
        String desc = g.path("description").asText("");
        if (!desc.isEmpty()) set.put("description", desc.length() > 1000 ? desc.substring(0, 1000) : desc);
        ArrayNode pv = set.putArray("pick_list_values");
        int seq = 0;
        for (JsonNode val : g.path("values")) {
            String v = val.asText("").trim();
            if (v.isEmpty()) continue;
            pv.addObject().put("display_value", v.length() > 120 ? v.substring(0, 120) : v).put("sequence_number", ++seq);
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("global_picklists").add(set);
        return payload;
    }

    /** The set in a GET global_picklists response whose api_name or display_label matches, or null. */
    static JsonNode existing(JsonNode list, String name) {
        if (list == null || name == null || name.trim().isEmpty()) return null;
        String want = name.trim();
        for (JsonNode g : list.path("global_picklists")) {
            if (g.path("api_name").asText("").equalsIgnoreCase(want) || g.path("display_label").asText("").equalsIgnoreCase(want)
                    || g.path("actual_label").asText("").equalsIgnoreCase(want)) return g;
        }
        return null;
    }
}
