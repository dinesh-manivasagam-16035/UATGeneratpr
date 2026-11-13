package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Picklist dependency through POST settings/layouts/{layout}/map_dependency?module=X (CRM v8): for one
 * parent/child pair, every parent option id with the child option ids it allows. Options the spec names
 * that the picklists lack are added first by the caller (ensurePicklistValue), then the fields re-read.
 */
final class ImplDependencies {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplDependencies() {}

    /** The option of a picklist field by actual or display value, or null. */
    static JsonNode option(JsonNode field, String value) {
        for (JsonNode pv : field.path("pick_list_values")) {
            if (value.equalsIgnoreCase(pv.path("actual_value").asText()) || value.equalsIgnoreCase(pv.path("display_value").asText())) return pv;
        }
        return null;
    }

    /** Options named in the mapping (parent keys, or child values) that the field doesn't have. */
    static List<String> missing(JsonNode field, Iterable<String> values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (option(field, v) == null && !out.contains(v)) out.add(v);
        return out;
    }

    static List<String> parentValues(JsonNode mapping) {
        List<String> out = new ArrayList<>();
        Iterator<String> it = mapping.fieldNames();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    static List<String> childValues(JsonNode mapping) {
        List<String> out = new ArrayList<>();
        for (JsonNode arr : mapping) for (JsonNode v : arr) if (!out.contains(v.asText())) out.add(v.asText());
        return out;
    }

    /** {"map_dependency":[{parent, child, pick_list_values:[{id, actual_value, display_value, maps:[{id, actual_value, display_value}]}]}]} */
    static ObjectNode payload(JsonNode parent, JsonNode child, JsonNode mapping) {
        ObjectNode d = MAPPER.createObjectNode();
        d.putObject("parent").put("api_name", parent.path("api_name").asText()).put("id", parent.path("id").asText());
        d.putObject("child").put("api_name", child.path("api_name").asText()).put("id", child.path("id").asText());
        ArrayNode pvs = d.putArray("pick_list_values");
        Iterator<Map.Entry<String, JsonNode>> it = mapping.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode po = option(parent, e.getKey());
            if (po == null) continue;
            ObjectNode pv = pvs.addObject();
            pv.put("id", po.path("id").asText());
            pv.put("actual_value", po.path("actual_value").asText());
            pv.put("display_value", po.path("display_value").asText());
            ArrayNode maps = pv.putArray("maps");
            for (JsonNode cv : e.getValue()) {
                JsonNode co = option(child, cv.asText());
                if (co == null) continue;
                maps.addObject().put("id", co.path("id").asText()).put("actual_value", co.path("actual_value").asText()).put("display_value", co.path("display_value").asText());
            }
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("map_dependency").add(d);
        return payload;
    }

    /** The existing dependency for the pair in a GET map_dependency list, or null. */
    static JsonNode existing(JsonNode list, String parentApi, String childApi) {
        if (list == null) return null;
        for (JsonNode d : list.path("map_dependency")) {
            if (d.path("parent").path("api_name").asText().equalsIgnoreCase(parentApi) && d.path("child").path("api_name").asText().equalsIgnoreCase(childApi)) return d;
        }
        return null;
    }
}
