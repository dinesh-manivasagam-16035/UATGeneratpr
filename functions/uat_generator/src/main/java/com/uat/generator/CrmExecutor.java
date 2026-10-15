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
 * If no real credentials are provided, nothing is sent anywhere: the case gets
 * a dry run instead (status "dry_run"), which checks that the plan could run:
 * known HTTP methods, paths, bodies for writes, variables captured before use,
 * and assertions with conditions. Earlier versions invented pass/fail results
 * here, which looked like real test outcomes.
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

        if (simulate) return dryRun(plan, result, trace);

        Map<String, String> captured = new HashMap<>();
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
        if (firstFailure != null) result.put("first_failure", firstFailure);
        return result;
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

    private static final java.util.Set<String> METHODS =
            new java.util.HashSet<>(java.util.Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE"));

    /**
     * Checks a plan without calling anything. "issues" would stop the plan from
     * running correctly; "notes" are weaknesses (e.g. a step with no assertions).
     */
    private static ObjectNode dryRun(JsonNode plan, ObjectNode result, ArrayNode trace) {
        java.util.Set<String> captured = new java.util.HashSet<>();
        int issueCount = 0;
        String firstIssue = null;

        for (int i = 0; i < plan.size(); i++) {
            JsonNode step = plan.get(i);
            ObjectNode sr = MAPPER.createObjectNode();
            String description = step.path("description").asText("step " + (i + 1));
            String method = step.path("method").asText("").toUpperCase();
            String path = step.path("path").asText("");
            JsonNode body = step.get("body");
            sr.put("description", description);
            sr.put("method", method.isEmpty() ? "?" : method);
            sr.put("path", path);
            sr.put("executed", false);
            ArrayNode issues = sr.putArray("issues");
            ArrayNode notes = sr.putArray("notes");

            if (!METHODS.contains(method)) issues.add("Unknown HTTP method \"" + method + "\"");
            if (path.isEmpty()) issues.add("Missing path");
            else if (!path.startsWith("/")) issues.add("Path should start with /");
            boolean write = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
            if (write && (body == null || body.isNull() || (body.isObject() && body.size() == 0))) {
                issues.add("No request body for " + method);
            }

            String varText = path + (body == null ? "" : body.toString());
            Matcher m = VAR.matcher(varText);
            java.util.Set<String> missing = new java.util.LinkedHashSet<>();
            while (m.find()) if (!captured.contains(m.group(1))) missing.add(m.group(1));
            for (String v : missing) issues.add("Uses {{" + v + "}} before any step captures it");

            JsonNode capture = step.get("capture");
            if (capture != null && !capture.isNull()) {
                if (!capture.isObject()) {
                    issues.add("capture must be an object of name → response path");
                } else {
                    java.util.Iterator<Map.Entry<String, JsonNode>> it = capture.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        if (!e.getValue().isTextual() || e.getValue().asText().trim().isEmpty()) {
                            issues.add("capture \"" + e.getKey() + "\" has no response path");
                        }
                        captured.add(e.getKey());
                    }
                }
            }

            JsonNode assertions = step.get("assertions");
            if (assertions == null || !assertions.isArray() || assertions.size() == 0) {
                notes.add("No assertions: this step can't fail on a wrong response");
            } else {
                for (JsonNode a : assertions) {
                    if (a.path("path").asText("").isEmpty()) issues.add("Assertion without a path");
                    else if (!a.has("equals") && !a.has("in") && !a.has("exists")) {
                        notes.add("Assertion on " + a.path("path").asText() + " has no condition");
                    }
                }
            }

            sr.put("ok", issues.size() == 0);
            if (issues.size() > 0) {
                issueCount += issues.size();
                if (firstIssue == null) firstIssue = description + ": " + issues.get(0).asText();
            }
            trace.add(sr);
        }

        result.put("status", "dry_run");
        result.put("dry_run", true);
        result.put("simulated", true);   // kept for older clients
        result.put("plan_ok", issueCount == 0);
        result.put("issue_count", issueCount);
        if (firstIssue != null) result.put("first_issue", firstIssue);
        return result;
    }
}
