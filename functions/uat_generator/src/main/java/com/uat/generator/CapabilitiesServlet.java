package com.uat.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * The capability sheet of a product, straight from its adapter — the same table that briefs the agent
 * and decides the build lane, so the sheet the team reads is never out of date with the code.
 *
 *   GET /implement/capabilities?platform=crm            → JSON {product, verified, rows:[…]}
 *   GET /implement/capabilities?platform=crm&format=csv → CSV download (liftoff-capabilities-crm.csv)
 */
public class CapabilitiesServlet extends HttpServlet {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.apply(req, resp);
        Product p = Products.byId(req.getParameter("platform") == null ? req.getParameter("product") : req.getParameter("platform"));
        if ("csv".equalsIgnoreCase(req.getParameter("format"))) {
            resp.setContentType("text/csv; charset=utf-8");
            resp.setHeader("Content-Disposition", "attachment; filename=\"liftoff-capabilities-" + p.id() + ".csv\"");
            resp.getWriter().write(csv(p));
            return;
        }
        resp.setContentType("application/json");
        resp.getWriter().write(MAPPER.writeValueAsString(json(p)));
    }

    public static ObjectNode json(Product p) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("product", p.id());
        out.put("label", p.label());
        out.put("builders_ready", p.buildersReady());
        ArrayNode rows = out.putArray("rows");
        for (Product.Capability c : p.capabilities()) {
            ObjectNode r = rows.addObject();
            r.put("kind", c.kind); r.put("activity", c.need); r.put("lane", c.lane); r.put("lane_label", c.laneLabel());
            r.put("api", c.api); r.put("scope", c.scope); r.put("status", c.status); r.put("how", c.how);
            r.put("doc", c.doc); r.put("verified", c.verified);
        }
        return out;
    }

    /** Columns: Activity, Lane today, API, Scope, LiftOff status, Notes, Doc, Verified, Kind. */
    public static String csv(Product p) {
        StringBuilder sb = new StringBuilder();
        sb.append("Product,Activity,Lane today,API (create/update),OAuth scope,LiftOff status,Notes,Reference,Verified,Kind\n");
        for (Product.Capability c : p.capabilities()) {
            List<String> cells = java.util.Arrays.asList(p.label(), c.need, c.laneLabel(), c.api, c.scope, statusLabel(c.status), c.how, c.doc, c.verified, c.kind);
            for (int i = 0; i < cells.size(); i++) { if (i > 0) sb.append(','); sb.append(q(cells.get(i))); }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String statusLabel(String s) {
        switch (s == null ? "" : s) {
            case "builds":  return "LiftOff builds it";
            case "partial": return "LiftOff builds it; one Setup step remains";
            case "runner":  return "No create API — guided runner / Zoho RPA";
            case "planned": return "API exists — LiftOff builder planned";
            case "verify":  return "API not confirmed — verify";
            default:        return s;
        }
    }

    private static String q(String s) {
        String v = s == null ? "" : s;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
