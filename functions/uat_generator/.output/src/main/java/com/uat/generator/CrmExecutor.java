package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the execution_plan of a single UAT case against a Zoho CRM org.
 *
 * Step shape (see system prompt in ClaudeClient):
 *   { description, method, path, body?, capture?, assertions[] }
 *
 * Variable substitution: {{name}} in path/body strings is replaced with the
 * captured value from a previous step in the same case.
 *
 * Assertion shape: { path, equals?, in?[], exists? }
 *  - path is a dotted/indexed path into the response. The synthetic key
 *    "status_code" returns the HTTP status. Otherwise it walks the JSON body.
 *
 * If no real credentials are provided, the executor simulates each step and
 * returns deterministic pass/fail based on whether the case looks negative
 * (tag "negative") or positive.
 */
public final class CrmExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VAR = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)\\}\\}");

    private CrmExecutor() {}

    public static ObjectNode execute(JsonNode testCase, CrmClient client) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode trace = result.putArray("trace");
        JsonNode plan = testCase.path("execution_plan");
        boolean simulate = client == null || !client.hasCredentials();

        if (!plan.isArray() || plan.size() == 0) {
            result.put("status", "skipped");
            result.put("reason", "No execution_plan in this case.");
            return result;
        }

        Map<String, String> captured = new HashMap<>();
        boolean negativeCase = looksNegative(testCase);
        boolean overallPass = true;
        String firstFailure = null;

        for (int i = 0; i < plan.size(); i++) {
            JsonNode step = plan.get(i);
            ObjectNode stepResult = MAPPER.createObjectNode();
            String description = step.path("description").asText("step " + (i + 1));
            String method = step.path("method").asText("GET");
            String path = substitute(step.path("path").asText(""), captured);
            String body = step.has("body") && !step.path("body").isNull()
                    ? substitute(step.path("body").toString(), captured)
                    : null;

            stepResult.put("description", description);
            stepResult.put("method", method);
            stepResult.put("path", path);
            if (body != null) stepResult.put("request_body", body);

            int statusCode;
            JsonNode responseJson;
            String responseBodyText;
            String execError = null;

            if (simulate) {
                ObjectNode sim = simulateStep(step, negativeCase, i, captured);
                statusCode = sim.path("__status").asInt(200);
                responseJson = sim.deepCopy();
                ((ObjectNode) responseJson).remove("__status");
                responseBodyText = responseJson.toString();
                stepResult.put("simulated", true);
            } else {
                try {
                    CrmClient.Response resp = client.call(method, path, body);
                    statusCode = resp.statusCode;
                    responseJson = resp.json;
                    responseBodyText = resp.rawBody;
                } catch (Exception ex) {
                    statusCode = 0;
                    responseJson = null;
                    responseBodyText = "";
                    execError = ex.getMessage();
                }
            }

            stepResult.put("status_code", statusCode);
            if (responseBodyText != null && responseBodyText.length() <= 4000) {
                stepResult.put("response_body", responseBodyText);
            } else if (responseBodyText != null) {
                stepResult.put("response_body", responseBodyText.substring(0, 4000) + "...(truncated)");
            }
            if (execError != null) stepResult.put("error", execError);

            JsonNode captureNode = step.path("capture");
            final JsonNode capturedFrom = responseJson;
            if (captureNode.isObject() && capturedFrom != null) {
                captureNode.fields().forEachRemaining(e -> {
                    String value = stringAt(capturedFrom, e.getValue().asText(""));
                    if (value != null) captured.put(e.getKey(), value);
                });
                stepResult.set("captured", MAPPER.valueToTree(captured));
            }

            ArrayNode assertionResults = stepResult.putArray("assertions");
            boolean stepPass = execError == null;
            JsonNode assertions = step.path("assertions");
            if (assertions.isArray()) {
                for (JsonNode a : assertions) {
                    ObjectNode ar = MAPPER.createObjectNode();
                    String aPath = substitute(a.path("path").asText(""), captured);
                    ar.put("path", aPath);
                    String actual = valueAt(responseJson, aPath, statusCode);
                    ar.put("actual", actual == null ? "(null)" : actual);

                    boolean ok;
                    if (a.has("equals")) {
                        String expected = substitute(a.get("equals").asText(), captured);
                        ar.put("expected", expected);
                        ok = actual != null && actual.equals(expected);
                    } else if (a.has("in") && a.get("in").isArray()) {
                        ArrayNode allowed = MAPPER.createArrayNode();
                        boolean any = false;
                        for (JsonNode v : a.get("in")) {
                            String exp = substitute(v.asText(), captured);
                            allowed.add(exp);
                            if (exp.equals(actual)) any = true;
                        }
                        ar.set("expected_in", allowed);
                        ok = any;
                    } else if (a.has("exists")) {
                        boolean want = a.get("exists").asBoolean(true);
                        ar.put("expected_exists", want);
                        ok = want ? actual != null : actual == null;
                    } else {
                        ar.put("expected", "(no condition)");
                        ok = true;
                    }
                    ar.put("ok", ok);
                    assertionResults.add(ar);
                    if (!ok) stepPass = false;
                }
            }
            stepResult.put("ok", stepPass);
            trace.add(stepResult);
            if (!stepPass && overallPass) {
                overallPass = false;
                firstFailure = description + (execError != null ? " (" + execError + ")" : "");
            }
        }

        result.put("status", overallPass ? "pass" : "fail");
        if (simulate) result.put("simulated", true);
        if (firstFailure != null) result.put("first_failure", firstFailure);
        return result;
    }

    private static boolean looksNegative(JsonNode tc) {
        JsonNode tags = tc.path("tags");
        if (tags.isArray()) {
            for (JsonNode t : tags) {
                String s = t.asText("").toLowerCase();
                if (s.equals("negative") || s.equals("validation") || s.equals("error")) return true;
            }
        }
        String title = tc.path("title").asText("").toLowerCase();
        return title.contains("reject") || title.contains("denied") || title.contains("invalid")
                || title.contains("missing required");
    }

    private static String substitute(String s, Map<String, String> vars) {
        if (s == null || s.isEmpty() || vars.isEmpty()) return s;
        Matcher m = VAR.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String repl = vars.getOrDefault(m.group(1), m.group(0));
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Returns string at "a.b[0].c" within json, or null. Synthetic key "status_code". */
    private static String valueAt(JsonNode root, String path, int statusCode) {
        if ("status_code".equals(path)) return String.valueOf(statusCode);
        return stringAt(root, path);
    }

    private static String stringAt(JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) return null;
        JsonNode cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || cur.isNull()) return null;
            String key = part;
            while (key.contains("[")) {
                int lb = key.indexOf('[');
                int rb = key.indexOf(']', lb);
                if (lb < 0 || rb < 0) break;
                if (lb > 0) {
                    String head = key.substring(0, lb);
                    cur = cur.path(head);
                }
                String idx = key.substring(lb + 1, rb);
                int i;
                try { i = Integer.parseInt(idx); } catch (Exception ex) { return null; }
                cur = cur.path(i);
                key = key.substring(rb + 1);
            }
            if (!key.isEmpty()) cur = cur.path(key);
        }
        if (cur == null || cur.isMissingNode() || cur.isNull()) return null;
        return cur.isValueNode() ? cur.asText() : cur.toString();
    }

    /** Synthesizes a plausible API response, including HTTP code under "__status". */
    private static ObjectNode simulateStep(JsonNode step, boolean negativeCase, int index,
                                           Map<String, String> captured) {
        ObjectNode out = MAPPER.createObjectNode();
        String method = step.path("method").asText("GET").toUpperCase();
        JsonNode assertions = step.path("assertions");
        boolean expectsError = negativeCase;

        if (assertions.isArray()) {
            for (JsonNode a : assertions) {
                String p = a.path("path").asText("");
                String eq = a.path("equals").asText("");
                if (p.equals("data[0].code") && "MANDATORY_NOT_FOUND".equals(eq)) expectsError = true;
            }
        }

        if (expectsError) {
            out.put("__status", 400);
            ArrayNode data = out.putArray("data");
            ObjectNode row = data.addObject();
            row.put("code", "MANDATORY_NOT_FOUND");
            row.put("status", "error");
            row.put("message", "required field missing");
            return out;
        }

        if (method.equals("POST")) {
            out.put("__status", 201);
            ArrayNode data = out.putArray("data");
            ObjectNode row = data.addObject();
            row.put("code", "SUCCESS");
            row.put("status", "success");
            row.put("message", "record added");
            ObjectNode details = row.putObject("details");
            String id = "MOCK-" + System.currentTimeMillis() + "-" + index;
            details.put("id", id);
            return out;
        }

        if (method.equals("GET") || method.equals("PATCH") || method.equals("PUT")
                || method.equals("DELETE")) {
            out.put("__status", 200);
            ArrayNode data = out.putArray("data");
            ObjectNode row = data.addObject();
            String rid = captured.getOrDefault("record_id", "MOCK-RECORD");
            row.put("id", rid);
            row.put("code", "SUCCESS");
            row.put("status", "success");
            return out;
        }
        out.put("__status", 200);
        return out;
    }
}
