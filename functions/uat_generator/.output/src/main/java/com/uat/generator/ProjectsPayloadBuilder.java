package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ProjectsPayloadBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProjectsPayloadBuilder() {}

    public static JsonNode build(JsonNode cases, String projectKey) {
        ObjectNode root = MAPPER.createObjectNode();
        if (projectKey != null && !projectKey.isEmpty()) {
            root.put("project_key", projectKey);
        }
        ArrayNode tasks = root.putArray("tasks");
        if (cases == null || !cases.isArray()) {
            return root;
        }
        for (JsonNode tc : cases) {
            ObjectNode task = tasks.addObject();
            task.put("name", tc.path("title").asText("UAT Case"));
            task.put("priority", mapPriority(tc.path("priority").asText("P1")));
            task.put("description", buildDescription(tc));
            ArrayNode tags = task.putArray("tags");
            JsonNode tagArr = tc.path("tags");
            if (tagArr.isArray()) {
                tagArr.forEach(t -> tags.add(t.asText()));
            }
            tags.add("uat");
        }
        return root;
    }

    private static String buildDescription(JsonNode tc) {
        StringBuilder sb = new StringBuilder();
        String gherkin = tc.path("gherkin").asText("");
        if (!gherkin.isEmpty()) sb.append("Gherkin:\n").append(gherkin).append("\n\n");
        JsonNode steps = tc.path("steps");
        if (steps.isArray() && steps.size() > 0) {
            sb.append("Steps:\n");
            int i = 1;
            for (JsonNode step : steps) {
                sb.append(i++).append(". ").append(step.path("action").asText(""))
                  .append(" -> ").append(step.path("expected").asText("")).append("\n");
            }
            sb.append("\n");
        }
        String acceptance = tc.path("acceptance").asText("");
        if (!acceptance.isEmpty()) sb.append("Acceptance:\n").append(acceptance);
        return sb.toString();
    }

    private static String mapPriority(String p) {
        if (p == null) return "Medium";
        switch (p.toUpperCase()) {
            case "P0": return "High";
            case "P2": return "Low";
            case "P1":
            default:   return "Medium";
        }
    }
}
