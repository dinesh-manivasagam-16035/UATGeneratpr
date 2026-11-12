package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Dependency-free regression checks for the pure parts of the implementation pipeline: parsing the
 * agent's JSON, deriving the API form of a rule, lanes vs. the capability matrix, the execution
 * plan, failure diagnostics and the runner's recipe parsers. Run with functions/uat_generator/selfcheck.sh.
 */
public final class SelfCheck {
    private static final ObjectMapper M = new ObjectMapper();
    private static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Products.use(M.readTree("{\"platform\":\"crm\"}"));
        parseAndNormalize();
        structuredRuleMirror();
        lanesAgreeWithMatrix(CrmProduct.INSTANCE);
        lanesAgreeWithMatrix(DeskProduct.INSTANCE);
        executionPlan();
        duplicateFunctions();
        diagnostics();
        recipeParsers();
        deskFieldMapping();
        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    // ---- checks ----

    static void parseAndNormalize() throws Exception {
        String reply = "```json\n{\"summary\":\"x\",\"fields\":[{\"module\":\"Leads\",\"label\":\"PIDG Code\",\"data_type\":\"text\",\"length\":50,"
                + "\"required\":false,\"assumption\":\"text, 50 characters, optional\",\"req_ids\":[\"FR-1\"]}],"
                + "\"workflows\":[{\"type\":\"workflow\",\"module\":\"Leads\",\"name\":\"Flag Leads Without PIDG Code\",\"trigger_type\":\"create\","
                + "\"conditions\":[{\"field\":\"PIDG_Code\",\"comparator\":\"is_empty\",\"value\":\"\"}],\"match\":\"and\","
                + "\"field_updates\":[{\"field\":\"Lead_Status\",\"value\":\"PIDG Pending\"}],\"tasks\":[],\"other_actions\":[],\"req_ids\":[\"FR-2\"]}],"
                + "\"open_questions\":[]}\n```";
        ImplSpec.Parsed p = ImplSpec.parse(reply, Arrays.asList("fields", "workflows", "open_questions"));
        check("parse: ok", p.ok());
        check("parse: one field", p.items.get("fields").size() == 1);
        ObjectNode f = p.items.get("fields").get(0);
        check("normalize: api_name derived", "PIDG_Code".equals(f.path("api_name").asText()));
        check("normalize: assumption kept", f.path("assumption").asText().startsWith("text, 50"));
        ObjectNode w = p.items.get("workflows").get(0);
        check("normalize: trigger mirror", "On create".equals(w.path("trigger").asText()));
        check("normalize: criteria mirror", w.path("criteria").asText().contains("PIDG_Code is empty"));
        check("normalize: actions mirror", w.path("actions").size() == 1 && w.path("actions").get(0).asText().contains("Lead_Status"));
    }

    static void structuredRuleMirror() throws Exception {
        // A rule the agent described only in words gets its API form from code.
        ObjectNode spec = emptySpec();
        ObjectNode w = M.createObjectNode().put("type", "workflow").put("module", "Leads").put("name", "Flag")
                .put("trigger", "On create").put("criteria", "When PIDG Code is empty");
        w.putArray("actions").add("Set Lead Status to PIDG Pending");
        w.putArray("field_updates"); w.putArray("tasks"); w.putArray("conditions"); w.putArray("other_actions"); w.putArray("req_ids");
        spec.withArray("workflows").add(w);
        spec.withArray("fields").add(M.createObjectNode().put("module", "Leads").put("label", "PIDG Code").put("api_name", "PIDG_Code").put("data_type", "text"));
        WorkflowStructurer.structureAll(spec, null);
        JsonNode r = spec.path("workflows").get(0);
        check("structurer: trigger_type", "create".equals(r.path("trigger_type").asText()));
        check("structurer: condition", r.path("conditions").size() == 1 && "is_empty".equals(r.path("conditions").get(0).path("comparator").asText()));
        check("structurer: field update", r.path("field_updates").size() == 1 && "PIDG Pending".equals(r.path("field_updates").get(0).path("value").asText()));
        check("structurer: lane api", "api".equals(CrmProduct.INSTANCE.laneFor("workflows", r)));
    }

    static void lanesAgreeWithMatrix(Product prod) {
        // Every capability lane the matrix advertises as "api" must have at least one item shape that laneFor accepts as api,
        // unless the product says its builders aren't ready (then everything but review/plan is rpa).
        boolean anyApi = false;
        for (Product.Capability c : prod.capabilities()) if ("api".equals(c.lane)) anyApi = true;
        if (!prod.buildersReady()) {
            ObjectNode f = M.createObjectNode().put("module", "Tickets").put("label", "x").put("data_type", "text").put("status", "new");
            check(prod.id() + ": builders not ready → rpa", "rpa".equals(prod.laneFor("fields", f)));
            return;
        }
        ObjectNode f = M.createObjectNode().put("module", "Leads").put("label", "x").put("data_type", "text").put("status", "new");
        check(prod.id() + ": text field is api", "api".equals(prod.laneFor("fields", f)));
        ObjectNode a = M.createObjectNode().put("type", "assignment").put("module", "Leads").put("status", "new");
        ObjectNode e = a.putArray("rule_entries").addObject(); e.putArray("conditions").addObject().put("field", "Email").put("comparator", "contains").put("value", "x"); e.putArray("assign_to").add("a@b.c");
        check(prod.id() + ": assignment rule is api", "api".equals(prod.laneFor("workflows", a)));
        ObjectNode lo = M.createObjectNode().put("module", "Leads").put("name", "Standard").put("status", "new");
        lo.putArray("sections").addObject().put("name", "PIDG").putArray("fields").add("PIDG Code"); lo.putArray("profiles");
        check(prod.id() + ": layout placement is api", "api".equals(prod.laneFor("layouts", lo)));
        ObjectNode vr = M.createObjectNode().put("module", "Leads").put("name", "v").put("status", "new");
        check(prod.id() + ": validation rule is rpa", "rpa".equals(prod.laneFor("validation_rules", vr)));
        check(prod.id() + ": matrix has api rows", anyApi);
        check(prod.id() + ": prompt carries the matrix", prod.systemPrompt("automation").contains("WHAT LIFTOFF CAN BUILD"));
    }

    static void executionPlan() {
        ObjectNode spec = emptySpec();
        spec.withArray("fields").add(M.createObjectNode().put("id", "FLD-1").put("module", "Leads").put("label", "PIDG Code").put("executor", "api").put("include", true));
        spec.withArray("validation_rules").add(M.createObjectNode().put("id", "VR-1").put("module", "Leads").put("name", "Flag").put("executor", "rpa").put("include", true));
        ObjectNode w = M.createObjectNode().put("id", "WF-1").put("module", "Leads").put("name", "Rule").put("type", "workflow").put("trigger_type", "create").put("executor", "api").put("include", true);
        w.putArray("field_updates").addObject().put("field", "Lead_Status").put("value", "PIDG Pending"); w.putArray("conditions"); w.putArray("tasks"); w.putArray("other_actions");
        spec.withArray("workflows").add(w);
        List<ObjectNode> plan = ImplPlan.derive(spec);
        check("plan: has steps", plan.size() >= 5);
        check("plan: api lane first", "api".equals(plan.get(0).path("lane").asText()));
        boolean uiStep = plan.stream().anyMatch((t) -> "ui".equals(t.path("lane").asText()) && t.path("spec_refs").toString().contains("VR-1"));
        check("plan: setup step for the validation rule", uiStep);
        check("plan: ends with UAT", "uat".equals(plan.get(plan.size() - 1).path("lane").asText()));
        check("plan: every step has verify text", plan.stream().allMatch((t) -> t.has("verify")));
    }

    static void duplicateFunctions() {
        ObjectNode spec = emptySpec();
        ObjectNode w = M.createObjectNode().put("id", "WF-1").put("module", "Leads").put("name", "Rule").put("executor", "api").put("include", true);
        w.putArray("req_ids").add("FR-2");
        spec.withArray("workflows").add(w);
        ObjectNode fn = M.createObjectNode().put("id", "FN-1").put("module", "Leads").put("name", "flag_pidg").put("include", true);
        fn.putArray("req_ids").add("FR-2");
        spec.withArray("functions").add(fn);
        ImplementServlet.excludeDuplicateFunctions(spec);
        check("dupes: function excluded", !spec.path("functions").get(0).path("include").asBoolean(true));
        check("dupes: note names the rule", spec.path("functions").get(0).path("note").asText().contains("Rule"));
        fn.put("include", true).put("manual_include", true);
        ImplementServlet.excludeDuplicateFunctions(spec);
        check("dupes: manual include respected", spec.path("functions").get(0).path("include").asBoolean());
    }

    static void diagnostics() throws Exception {
        ObjectNode body = M.createObjectNode();
        ImplDiagnose.StepFailure sf = new ImplDiagnose.StepFailure("field update: HTTP 400: the value given seems to be invalid",
                "POST", "/crm/v8/settings/automation/field_updates", body, 400,
                "{\"field_updates\":[{\"code\":\"INVALID_DATA\",\"details\":{\"api_name\":\"value\",\"json_path\":\"$.field_updates[0].value\"},\"message\":\"the value given seems to be invalid\"}]}");
        ObjectNode f = ImplDiagnose.describe(sf, "workflows", M.createObjectNode());
        check("diagnose: picklist cause", f.path("cause").asText().contains("picklist"));
        check("diagnose: checks present", f.path("checks").size() >= 2);
        check("diagnose: response kept", f.path("response").path("status").asInt() == 400);
        ObjectNode g = ImplDiagnose.describe(new IllegalStateException("HTTP 401: OAUTH_SCOPE_MISMATCH"), "fields", M.createObjectNode());
        check("diagnose: scope cause", g.path("cause").asText().toLowerCase().contains("scope"));
    }

    static void recipeParsers() throws Exception {
        java.lang.reflect.Method fl = ImplRpa.class.getDeclaredMethod("fieldLabel", String.class); fl.setAccessible(true);
        java.lang.reflect.Method cw = ImplRpa.class.getDeclaredMethod("conditionWord", String.class); cw.setAccessible(true);
        check("recipe: field from 'PIDG Code is empty'", "PIDG Code".equals(fl.invoke(null, "PIDG Code is empty")));
        check("recipe: field from 'when Amount > 1000'", "Amount".equals(fl.invoke(null, "when Amount > 1000")));
        check("recipe: 'is not empty' before 'is empty'", "is not empty".equals(cw.invoke(null, "Email is not empty")));
        ObjectNode vr = M.createObjectNode().put("module", "Leads").put("name", "Flag").put("condition", "PIDG Code is empty").put("error_message", "Required");
        check("recipe: validation rule has steps", ImplRpa.recipe("validation_rules", vr).size() >= 8);
        check("recipe: blueprint is manual", ImplRpa.recipe("workflows", M.createObjectNode().put("type", "blueprint")).size() == 0);
    }

    static void deskFieldMapping() throws Exception {
        JsonNode f = M.readTree("{\"id\":\"1\",\"apiName\":\"cf_escalation_status\",\"displayLabel\":\"Escalation Status\",\"type\":\"Picklist\",\"isCustomField\":true,\"allowedValues\":[\"None\",\"L1\",\"L2\"]}");
        JsonNode c = DeskOrg.toCrmField(f);
        check("desk: picklist type", "picklist".equals(c.path("data_type").asText()));
        check("desk: options carried", c.path("pick_list_values").size() == 3);
        check("desk: label", "Escalation Status".equals(c.path("field_label").asText()));
        Products.use(M.readTree("{\"platform\":\"desk\"}"));
        check("desk: adapter selected", "desk".equals(Products.current().id()));
        check("desk: setup menu for SLA", Products.current().automationMenu("sla").contains("SLA"));
        Products.use(M.readTree("{\"platform\":\"crm\"}"));
    }

    // ---- helpers ----

    static ObjectNode emptySpec() {
        ObjectNode s = M.createObjectNode();
        for (String sct : ImplSpec.SECTIONS) s.putArray(sct);
        return s;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (ok) pass++; else fail++;
    }
}
