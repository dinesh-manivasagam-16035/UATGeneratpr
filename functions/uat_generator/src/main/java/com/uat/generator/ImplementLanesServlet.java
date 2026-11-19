package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Which lane each blueprint item is in with the code that is running now.
 *
 *   POST /implement/lanes  {spec, platform?}  →  {build, lanes: {"FLD-1": "api", "VAL-1": "rpa", …}}
 *
 * The browser keeps the blueprint between steps, so the lanes on it can be older than this build
 * (a blueprint made before a builder existed) or stale after an item was edited by hand. The page
 * asks here when it restores a saved blueprint and after an edit, instead of carrying its own copy
 * of the lane rules. Nothing is read from or written to the org.
 */
public class ImplementLanesServlet extends HttpServlet {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);
        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            Products.use(body);
            JsonNode spec = body.path("spec");
            if (!spec.isObject()) {
                ImplementServlet.writeError(resp, 400, "Field 'spec' (the blueprint from /implement) is required.");
                return;
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.put("build", BuildInfo.VERSION);
            out.put("product", Products.current().id());
            out.set("lanes", ImplSpec.lanes(spec));
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            ImplementServlet.writeError(resp, 500, e.getMessage());
        }
    }
}
