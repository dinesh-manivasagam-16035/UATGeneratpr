package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Deal pipeline through POST settings/pipeline?layout_id=… (CRM v8). The stages of a pipeline are existing
 * values of the Deals Stage picklist referenced by id, in sequence; stages the org lacks are added to the
 * picklist first (ensurePicklistValue), then the field is re-read for their ids.
 */
final class ImplPipeline {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplPipeline() {}

    /** {"pipeline":[{display_value, default, maps:[{sequence_number, id, display_value}]}]} */
    static ObjectNode payload(JsonNode wf, List<String> stages, Map<String, String> optionIds) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("display_value", wf.path("name").asText());
        p.put("default", wf.path("default").asBoolean(false));
        ArrayNode maps = p.putArray("maps");
        int seq = 0;
        for (String s : stages) {
            if (!optionIds.containsKey(s)) continue;
            maps.addObject().put("sequence_number", ++seq).put("id", optionIds.get(s)).put("display_value", s);
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("pipeline").add(p);
        return payload;
    }

    /** The existing pipeline with this name on the layout, or null. */
    static JsonNode existing(JsonNode pipelines, String name) {
        if (pipelines == null) return null;
        for (JsonNode p : pipelines.path("pipeline")) {
            if (p.path("display_value").asText("").equalsIgnoreCase(name) || p.path("actual_value").asText("").equalsIgnoreCase(name)) return p;
        }
        return null;
    }
}
