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

/**
 * Blueprint through POST settings/blueprints (CRM v8). The spec gives states in order and, optionally,
 * the picklist field they map to and named transitions; this class picks the field, maps each state to
 * a picklist option id and lays the process out as a chain (or the transitions given), with chart data
 * so the Setup editor can draw it. The API creates it in Draft; activation is a second call.
 */
final class ImplBlueprint {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplBlueprint() {}

    /** States in order, as the spec names them ("states" strings, or transitions' from/to when states are absent). */
    static List<String> states(JsonNode wf) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : wf.path("states")) { String v = s.isObject() ? s.path("name").asText("") : s.asText(""); if (!v.trim().isEmpty() && !out.contains(v.trim())) out.add(v.trim()); }
        if (out.isEmpty()) for (JsonNode t : wf.path("transitions")) for (String k : new String[]{"from", "to"}) {
            String v = t.path(k).asText("").trim(); if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    /**
     * The picklist field the states map to: the one the spec names, else the module's picklist whose options
     * cover most states (ties → Lead_Status / Stage / Status), else null.
     */
    static JsonNode pickField(JsonNode wf, List<JsonNode> fields, List<String> states) {
        String want = wf.path("field").asText("");
        if (!want.isEmpty()) for (JsonNode f : fields) {
            if (f.path("api_name").asText().equalsIgnoreCase(want) || f.path("field_label").asText().equalsIgnoreCase(want)) return f;
        }
        JsonNode best = null; int bestHits = -1;
        for (JsonNode f : fields) {
            if (!f.path("data_type").asText("").equals("picklist")) continue;
            int hits = 0;
            for (JsonNode pv : f.path("pick_list_values")) for (String s : states) {
                if (s.equalsIgnoreCase(pv.path("actual_value").asText()) || s.equalsIgnoreCase(pv.path("display_value").asText())) hits++;
            }
            boolean preferred = f.path("api_name").asText().matches("Lead_Status|Stage|Status|Case_Status");
            if (hits > bestHits || (hits == bestHits && preferred)) { best = f; bestHits = hits; }
        }
        return bestHits > 0 || (best != null && best.path("api_name").asText().matches("Lead_Status|Stage|Status|Case_Status")) ? best : null;
    }

    /** State names → picklist option ids on the chosen field (missing ones are absent from the map). */
    static Map<String, String> optionIds(JsonNode field, List<String> states) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String s : states) for (JsonNode pv : field.path("pick_list_values")) {
            if (s.equalsIgnoreCase(pv.path("actual_value").asText()) || s.equalsIgnoreCase(pv.path("display_value").asText())) { ids.put(s, pv.path("id").asText()); break; }
        }
        return ids;
    }

    static String apiName(String name, String prefix, int i) {
        String n = name.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) n = prefix + "_" + i;
        return n.length() > 50 ? n.substring(0, 50) : n;
    }

    /**
     * The create payload. {@code transitions} come from the spec ([{name, from, to}]) or default to one manual
     * transition between each consecutive pair of states.
     */
    static ObjectNode payload(JsonNode wf, String moduleApi, String moduleId, JsonNode layout, JsonNode field,
                              List<String> states, Map<String, String> optionIds) {
        ObjectNode bp = MAPPER.createObjectNode();
        bp.put("name", wf.path("name").asText());
        bp.put("api_name", apiName(wf.path("name").asText(), "Blueprint", 1));
        String desc = wf.path("details").asText("");
        if (desc.isEmpty()) desc = wf.path("trigger").asText("");
        if (!desc.isEmpty()) bp.put("description", desc.length() > 2000 ? desc.substring(0, 2000) : desc);
        bp.put("continuous", false);
        bp.putObject("module").put("id", moduleId).put("api_name", moduleApi);
        bp.putObject("layout").put("id", layout.path("id").asText()).put("api_name", layout.path("api_name").asText(layout.path("name").asText())).put("name", layout.path("name").asText());
        bp.putObject("field").put("id", field.path("id").asText()).put("api_name", field.path("api_name").asText());

        Map<String, String> stateApi = new LinkedHashMap<>();
        ArrayNode st = bp.putArray("states");
        int i = 0;
        for (String s : states) {
            i++;
            String api = apiName(s, "State", i);
            while (stateApi.containsValue(api)) api = api + "_" + i;
            stateApi.put(s, api);
            ObjectNode o = st.addObject();
            o.put("name", s);
            o.put("api_name", api);
            o.putObject("module").put("api_name", moduleApi);
            if (optionIds.containsKey(s)) o.putObject("pick_list_value").put("id", optionIds.get(s)).put("actual_value", s);
        }

        List<String[]> edges = new ArrayList<>();     // {name, from, to}
        for (JsonNode t : wf.path("transitions")) {
            String from = t.path("from").asText("").trim(), to = t.path("to").asText("").trim();
            if (stateApi.containsKey(from) && stateApi.containsKey(to)) edges.add(new String[]{t.path("name").asText("To " + to), from, to});
        }
        if (edges.isEmpty()) for (int k = 0; k + 1 < states.size(); k++) edges.add(new String[]{"To " + states.get(k + 1), states.get(k), states.get(k + 1)});

        ArrayNode tr = bp.putArray("transitions");
        ArrayNode cn = bp.putArray("connections");
        List<String> usedTransitions = new ArrayList<>();
        int k = 0;
        for (String[] e : edges) {
            k++;
            String tapi = apiName(e[0], "Transition", k);
            while (usedTransitions.contains(tapi)) tapi = tapi + "_" + k;
            usedTransitions.add(tapi);
            ObjectNode t = tr.addObject();
            t.put("name", e[0]);
            t.put("api_name", tapi);
            t.put("transition_type", "standalone_transition");
            t.put("trigger_type", "manual");
            t.putObject("module").put("api_name", moduleApi).put("id", moduleId);
            t.putObject("layout").put("api_name", layout.path("api_name").asText(layout.path("name").asText())).put("name", layout.path("name").asText()).put("id", layout.path("id").asText());
            ObjectNode c = cn.addObject();
            c.put("api_name", "Connection_" + k);
            c.putObject("from_state").put("api_name", stateApi.get(e[1])).put("name", e[1]);
            c.putObject("to_state").put("api_name", stateApi.get(e[2])).put("name", e[2]);
            c.putObject("transitions").put("api_name", tapi).put("name", e[0]).put("precedence", k);
        }

        // Chart so the Setup editor can draw it: states left to right, one row.
        ObjectNode chart = bp.putObject("chart_data");
        chart.putObject("canvas_size").put("width", Math.max(900, 260 * states.size() + 200)).put("height", 420);
        ArrayNode nodes = chart.putArray("nodes");
        Map<String, Integer> x = new LinkedHashMap<>();
        int n = 0;
        for (String s : states) { int px = 120 + 260 * n++; x.put(s, px); ObjectNode nd = nodes.addObject(); nd.put("position_x", px); nd.put("position_y", 180); nd.putObject("state").put("api_name", stateApi.get(s)); }
        ArrayNode cc = chart.putArray("connections");
        k = 0;
        for (String[] e : edges) {
            k++;
            ObjectNode c = cc.addObject();
            c.put("api_name", "Connection_" + k);
            c.putObject("transition").put("api_name", usedTransitions.get(k - 1)).put("name", e[0]);
            ObjectNode from = c.putObject("from"); from.putObject("state").put("api_name", stateApi.get(e[1]));
            from.putObject("end_point").put("position_x", x.get(e[1]) + 160).put("position_y", 200).put("direction_x", 1).put("direction_y", 0);
            ObjectNode to = c.putObject("to"); to.putObject("state").put("api_name", stateApi.get(e[2]));
            to.putObject("end_point").put("position_x", x.get(e[2])).put("position_y", 200).put("direction_x", -1).put("direction_y", 0);
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("blueprints").add(bp);
        return payload;
    }

    /** Activation body for a first publish: no records to move. */
    static ObjectNode activateBody() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.putArray("blueprints").addObject().put("move_records", false);
        return payload;
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
