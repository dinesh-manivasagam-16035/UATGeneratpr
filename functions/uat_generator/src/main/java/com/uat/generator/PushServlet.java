package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /push
 *
 * For each case:
 *   - Always creates a Projects task.
 *   - If the case carries an `execution_result.status == "fail"`, additionally
 *     creates a Bug in the Projects Bug Tracker app, linked to the parent task
 *     via `associated_taskids`.
 *
 * Body: {
 *   "portal_id":  "...",
 *   "project_id": "...",
 *   "cases":      [ ... cases (post-execution if available) ... ]
 * }
 */
public class PushServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(PushServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String portalId = text(body, "portal_id",
                    firstNonEmpty(System.getenv("ZOHO_PROJECTS_PORTAL_ID"),
                                  ProjectDefaults.portal()));
            String projectId = text(body, "project_id",
                    firstNonEmpty(System.getenv("ZOHO_PROJECTS_PROJECT_ID"),
                                  ProjectDefaults.project()));
            JsonNode cases = body.get("cases");

            if (cases == null || !cases.isArray()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Field 'cases' (array) is required.");
                return;
            }
            if (portalId == null || projectId == null) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "No portal/project configured. Set ZOHO_PROJECTS_PORTAL_ID + "
                                + "ZOHO_PROJECTS_PROJECT_ID env vars, or place values "
                                + "in project-defaults.properties.");
                return;
            }

            boolean haveProjectsCreds = System.getenv("ZOHO_REFRESH_TOKEN") != null;
            ProjectsClient projects = haveProjectsCreds ? ProjectsClient.fromEnv() : null;
            BugsClient bugs = haveProjectsCreds ? BugsClient.fromEnv() : null;

            if (projects != null) {
                try {
                    portalId = projects.resolvePortalId(portalId);
                } catch (Exception ex) {
                    LOG.log(Level.WARNING, "portal slug -> id resolution failed", ex);
                    writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                            "Could not resolve portal '" + portalId + "': " + ex.getMessage());
                    return;
                }
            }

            ArrayNode results = MAPPER.createArrayNode();
            int tasksCreated = 0, tasksFailed = 0, bugsCreated = 0, bugsFailed = 0;

            for (JsonNode tc : cases) {
                ObjectNode r = MAPPER.createObjectNode();
                r.put("title", tc.path("title").asText(""));
                String execStatus = tc.path("execution_result").path("status").asText("");
                r.put("execution_status", execStatus);

                String taskId;
                try {
                    if (projects == null) {
                        taskId = "MOCK-TASK-" + System.currentTimeMillis() + "-" + tasksCreated;
                        r.put("task_status", "mock");
                    } else {
                        taskId = projects.createTask(portalId, projectId, withExecutionContext(tc));
                        r.put("task_status", "created");
                    }
                    r.put("task_id", taskId);
                    tasksCreated++;
                } catch (Exception ex) {
                    LOG.log(Level.WARNING, "Failed to create task", ex);
                    r.put("task_status", "failed");
                    r.put("task_error", ex.getMessage());
                    results.add(r);
                    tasksFailed++;
                    continue;
                }

                if ("fail".equals(execStatus)) {
                    try {
                        ObjectNode bugReq = buildBugRequest(tc, taskId);
                        String bugId;
                        if (bugs == null) {
                            bugId = "MOCK-BUG-" + System.currentTimeMillis() + "-" + bugsCreated;
                            r.put("bug_status", "mock");
                        } else {
                            bugId = bugs.createBug(portalId, projectId, bugReq);
                            r.put("bug_status", "created");
                        }
                        r.put("bug_id", bugId);
                        bugsCreated++;
                    } catch (Exception ex) {
                        LOG.log(Level.WARNING, "Failed to create bug", ex);
                        r.put("bug_status", "failed");
                        r.put("bug_error", ex.getMessage());
                        bugsFailed++;
                    }
                }
                results.add(r);
            }

            ObjectNode out = MAPPER.createObjectNode();
            out.put("tasks_created", tasksCreated);
            out.put("tasks_failed", tasksFailed);
            out.put("bugs_created", bugsCreated);
            out.put("bugs_failed", bugsFailed);
            out.put("created", tasksCreated);
            out.put("failed", tasksFailed);
            out.put("mock", projects == null);
            out.set("results", results);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "push failed", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    /**
     * Returns a copy of the case with execution status appended to the title and
     * a summary line glued onto the description, so the Projects task itself
     * carries the pass/fail signal.
     */
    private static JsonNode withExecutionContext(JsonNode tc) {
        if (!tc.isObject()) return tc;
        ObjectNode copy = ((ObjectNode) tc).deepCopy();
        String status = tc.path("execution_result").path("status").asText("");
        if (!status.isEmpty()) {
            String prefix = status.equals("pass") ? "[PASS] "
                          : status.equals("fail") ? "[FAIL] "
                          : "[" + status.toUpperCase() + "] ";
            copy.put("title", prefix + tc.path("title").asText(""));
        }
        return copy;
    }

    private static ObjectNode buildBugRequest(JsonNode tc, String associatedTaskId) {
        ObjectNode req = MAPPER.createObjectNode();
        String title = "[UAT FAIL] " + tc.path("title").asText("Untitled");
        req.put("title", title);
        req.put("description", buildBugDescription(tc));
        req.put("severity", deriveSeverity(tc));
        req.put("classification", "Bug");
        req.put("steps_to_reproduce", reproSteps(tc));
        if (associatedTaskId != null && !associatedTaskId.isEmpty()
                && !associatedTaskId.startsWith("MOCK-")) {
            req.put("associated_taskids", associatedTaskId);
        }
        return req;
    }

    private static String buildBugDescription(JsonNode tc) {
        StringBuilder sb = new StringBuilder();
        sb.append("UAT case failed during automated execution against Zoho CRM.\n\n");
        String firstFailure = tc.path("execution_result").path("first_failure").asText("");
        if (!firstFailure.isEmpty()) {
            sb.append("First failure: ").append(firstFailure).append("\n\n");
        }
        sb.append("Acceptance:\n").append(tc.path("acceptance").asText("(none)")).append("\n\n");
        JsonNode trace = tc.path("execution_result").path("trace");
        if (trace.isArray() && trace.size() > 0) {
            sb.append("Trace summary:\n");
            int i = 1;
            for (JsonNode step : trace) {
                sb.append(i++).append(". ")
                  .append(step.path("ok").asBoolean() ? "[ok]   " : "[FAIL] ")
                  .append(step.path("method").asText("")).append(" ")
                  .append(step.path("path").asText("")).append(" -> ")
                  .append(step.path("status_code").asInt()).append("\n");
            }
            sb.append("\n");
        }
        String gherkin = tc.path("gherkin").asText("");
        if (!gherkin.isEmpty()) sb.append("Original scenario:\n").append(gherkin);
        return sb.toString();
    }

    private static String deriveSeverity(JsonNode tc) {
        String priority = tc.path("priority").asText("P1").toUpperCase();
        switch (priority) {
            case "P0": return "High";
            case "P2": return "Low";
            default:   return "Medium";
        }
    }

    private static String reproSteps(JsonNode tc) {
        JsonNode steps = tc.path("steps");
        if (!steps.isArray() || steps.size() == 0) return "";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode s : steps) {
            sb.append(i++).append(". ").append(s.path("action").asText(""))
              .append(" -> ").append(s.path("expected").asText("")).append("\n");
        }
        return sb.toString();
    }

    private static String text(JsonNode body, String field, String fallback) {
        JsonNode n = body.get(field);
        String v = n == null || n.isNull() ? "" : n.asText("").trim();
        if (!v.isEmpty()) return v;
        return fallback == null || fallback.isEmpty() ? null : fallback;
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
