package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * The Creator build lane: the blueprint as an application file the person imports in Creator.
 *
 *   POST /implement/creator-ds  {spec, app_name?}
 *     → {filename, application, ds, forms[], reports[], workflows[], unverified[], skipped[{id,label,why}]}
 *
 * Nothing is written to Creator from here — its API can't create structure; the import is the one step.
 */
public class CreatorDsServlet extends HttpServlet {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);
        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            JsonNode spec = body.path("spec");
            if (!spec.isObject()) {
                ImplementServlet.writeError(resp, 400, "Field 'spec' (the blueprint from /implement) is required.");
                return;
            }
            String app = body.path("app_name").asText("").trim();
            if (app.isEmpty()) app = "LiftOff Application";
            CreatorDs.Result res = CreatorDs.generate(spec, app);
            if (res.ds.isEmpty()) {
                ImplementServlet.writeError(resp, 422, "The blueprint has no new form with fields, so there is nothing to put in an application file."
                        + (res.skipped.isEmpty() ? "" : " First reason: " + res.skipped.get(0)[2] + "."));
                return;
            }
            resp.getWriter().write(MAPPER.writeValueAsString(res.json(app)));
        } catch (Exception e) {
            ImplementServlet.writeError(resp, 500, e.getMessage());
        }
    }
}
