package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

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
        functionDefinitions();
        blueprintPayload();
        profilePermissions();
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
        boolean consistent = true;
        for (Product.Capability c : prod.capabilities()) {
            if (!Arrays.asList("api", "partial", "ui").contains(c.lane)) consistent = false;
            if ("builds".equals(c.status) && !"api".equals(c.lane)) consistent = false;        // builds ⇒ API lane
            if ("planned".equals(c.status) && "api".equals(c.lane)) consistent = false;         // planned ⇒ not yet an API lane
            if ("planned".equals(c.status) && c.api.isEmpty()) consistent = false;              // planned needs the endpoint it will call
        }
        check(prod.id() + ": sheet rows consistent (lane vs status vs api)", consistent);
    }

    static void profilePermissions() {
        ArrayNode perms = M.createArrayNode().add("View Leads").add("Create and Edit Deals").add("No Delete on Deals").add("Export").add("Fly to the moon");
        List<ImplProfiles.Want> wants = ImplProfiles.parse(perms);
        check("profiles: words parsed into actions", wants.size() == 6 && "View".equals(wants.get(0).action) && "Leads".equals(wants.get(0).module)
                && "Create".equals(wants.get(1).action) && "Edit".equals(wants.get(2).action) && "Deals".equals(wants.get(2).module)
                && "Delete".equals(wants.get(3).action) && !wants.get(3).enabled && "Export".equals(wants.get(4).action) && wants.get(4).module.isEmpty());
        ArrayNode pds = M.createArrayNode();
        pds.addObject().put("id", "1").put("name", "Crm_Implied_View_Leads").put("display_label", "View").put("module", "Leads").put("enabled", false);
        pds.addObject().put("id", "2").put("name", "Crm_Implied_Create_Deals").put("display_label", "Create").put("module", "Deals").put("enabled", false);
        pds.addObject().put("id", "3").put("name", "Crm_Implied_Edit_Deals").put("display_label", "Edit").put("module", "Deals").put("enabled", false);
        pds.addObject().put("id", "4").put("name", "Crm_Implied_Delete_Deals").put("display_label", "Delete").put("module", "Deals").put("enabled", true);
        pds.addObject().put("id", "5").put("name", "Crm_Implied_Delete_Leads").put("display_label", "Delete").put("module", "Leads").put("enabled", true);
        pds.addObject().put("id", "6").put("name", "Crm_Implied_Export").put("display_label", "Export").put("module", "null").put("enabled", false);
        ObjectNode m = ImplProfiles.match(wants, pds, null);
        List<String> ids = new java.util.ArrayList<>(); for (JsonNode c : m.path("changes")) ids.add(c.path("id").asText() + ":" + c.path("enabled").asBoolean());
        check("profiles: matched by module + action, delete only on Deals, export module-less", ids.equals(Arrays.asList("1:true", "2:true", "3:true", "4:false", "6:true")));
        check("profiles: unmatched reported", m.path("unmatched").size() == 1 && m.path("unmatched").path(0).asText().startsWith("Fly"));
        ObjectNode body = ImplProfiles.updateBody(m.withArray("changes"));
        check("profiles: PUT body shape", body.path("profiles").path(0).path("permissions_details").size() == 5 && body.path("profiles").path(0).path("permissions_details").path(0).has("enabled"));
        check("profiles: clone body", "Sales Rep".equals(ImplProfiles.cloneBody("Sales Rep", "").path("profiles").path(0).path("name").asText()));
        check("crm: role with permissions is api", "api".equals(CrmProduct.INSTANCE.laneFor("roles", M.createObjectNode().put("status", "new").set("permissions", perms))));
    }

    static void blueprintPayload() {
        ObjectNode wf = M.createObjectNode().put("type", "blueprint").put("module", "Leads").put("name", "Lead Qualification");
        wf.putArray("states").add("New").add("Contacted").add("Qualified");
        ObjectNode fieldA = M.createObjectNode().put("api_name", "Lead_Status").put("field_label", "Lead Status").put("data_type", "picklist").put("id", "f1");
        ArrayNode pv = fieldA.putArray("pick_list_values");
        pv.addObject().put("id", "p1").put("actual_value", "New").put("display_value", "New");
        pv.addObject().put("id", "p2").put("actual_value", "Contacted").put("display_value", "Contacted");
        ObjectNode fieldB = M.createObjectNode().put("api_name", "Rating").put("data_type", "picklist").put("id", "f2");
        fieldB.putArray("pick_list_values").addObject().put("id", "p9").put("actual_value", "Hot").put("display_value", "Hot");
        List<String> states = ImplBlueprint.states(wf);
        JsonNode picked = ImplBlueprint.pickField(wf, Arrays.asList(fieldB, fieldA), states);
        check("blueprint: picks the picklist covering the states", picked != null && "Lead_Status".equals(picked.path("api_name").asText()));
        check("blueprint: option ids mapped, missing state absent", ImplBlueprint.optionIds(fieldA, states).size() == 2);
        pv.addObject().put("id", "p3").put("actual_value", "Qualified").put("display_value", "Qualified");
        ObjectNode layout = M.createObjectNode().put("id", "L1").put("name", "Standard").put("api_name", "Standard");
        ObjectNode body = ImplBlueprint.payload(wf, "Leads", "M1", layout, fieldA, states, ImplBlueprint.optionIds(fieldA, states)).path("blueprints").path(0).deepCopy();
        check("blueprint: 3 states, 2 chain transitions, 2 connections", body.path("states").size() == 3 && body.path("transitions").size() == 2 && body.path("connections").size() == 2);
        check("blueprint: state carries picklist id and module", "p2".equals(body.path("states").path(1).path("pick_list_value").path("id").asText()) && "Leads".equals(body.path("states").path(1).path("module").path("api_name").asText()));
        check("blueprint: connection links states by api_name through its transition", body.path("connections").path(0).path("from_state").path("api_name").asText().equals(body.path("states").path(0).path("api_name").asText())
                && body.path("connections").path(0).path("transitions").path("api_name").asText().equals(body.path("transitions").path(0).path("api_name").asText()));
        check("blueprint: chart nodes match states", body.path("chart_data").path("nodes").size() == 3 && body.path("chart_data").path("connections").size() == 2);
        check("blueprint: field, layout and module referenced by id", "f1".equals(body.path("field").path("id").asText()) && "L1".equals(body.path("layout").path("id").asText()) && "M1".equals(body.path("module").path("id").asText()));
        check("crm: blueprint lane is partial", "partial".equals(CrmProduct.INSTANCE.laneFor("workflows", wf.deepCopy().put("status", "new"))));
    }

    static void functionDefinitions() {
        ObjectNode fn = M.createObjectNode().put("name", "set_pidg_code").put("module", "Leads")
                .put("trigger", "automation: on create of Leads").put("purpose", "Fills PIDG Code")
                .put("code", "lead = zoho.crm.getRecordById(\"Leads\", leadId);\nmp = Map();\nmp.put(\"PIDG_Code\", \"X\");\nzoho.crm.updateRecord(\"Leads\", leadId, mp);");
        fn.putArray("arguments").addObject().put("name", "leadId").put("type", "int");
        String def = ImplFunctions.definition(fn);
        check("functions: declaration from category and name", def.startsWith("void automation.set_pidg_code(int leadId)"));
        check("functions: body indented inside braces", def.contains("{\n\tlead = zoho.crm") && def.endsWith("}"));
        ObjectNode md = ImplFunctions.metadata(fn).path("functions").path(0).deepCopy();
        check("functions: metadata has runtime, category and _code", "Deluge 1.0".equals(md.path("runtime").asText()) && "Automation".equals(md.path("category").asText()) && md.path("_code").asText().contains("set_pidg_code"));
        ObjectNode btn = M.createObjectNode().put("name", "Send Quote").put("module", "Deals").put("trigger", "button on Deals").put("code", "return \"ok\";");
        check("functions: button → string return, record id argument", ImplFunctions.definition(btn).startsWith("string button.send_quote(int dealId)"));
        ObjectNode declared = M.createObjectNode().put("name", "x").put("code", "void automation.x(int id)\n{\n}");
        check("functions: declared body kept as is", ImplFunctions.definition(declared).startsWith("void automation.x(int id)"));
        check("crm: functions lane is api", "api".equals(CrmProduct.INSTANCE.laneFor("functions", M.createObjectNode().put("status", "new"))));
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
        // a function twinning a validation rule, and a workflow with no action at all
        ObjectNode spec2 = emptySpec();
        ObjectNode vr = M.createObjectNode().put("id", "VAL-1").put("module", "Leads").put("name", "Block qualification").put("include", true); vr.putArray("req_ids").add("FR-3");
        spec2.withArray("validation_rules").add(vr);
        ObjectNode fn2 = M.createObjectNode().put("id", "FN-2").put("module", "Leads").put("name", "validate_on_qualify").put("include", true); fn2.putArray("req_ids").add("FR-3");
        spec2.withArray("functions").add(fn2);
        ObjectNode w2 = M.createObjectNode().put("id", "WF-2").put("type", "workflow").put("module", "Leads").put("name", "Block qualification").put("trigger_type", "field_update").put("include", true);
        w2.putArray("req_ids").add("FR-3"); w2.putArray("field_updates"); w2.putArray("tasks"); w2.putArray("webhooks"); w2.putArray("other_actions"); w2.putArray("actions");
        spec2.withArray("workflows").add(w2);
        ImplementServlet.excludeEmptyWorkflows(spec2);
        ImplementServlet.excludeDuplicateFunctions(spec2);
        check("dupes: function twinning a validation rule excluded", !spec2.path("functions").get(0).path("include").asBoolean(true));
        check("empty workflow: excluded with a pointer to the validation rule", !spec2.path("workflows").get(0).path("include").asBoolean(true) && spec2.path("workflows").get(0).path("note").asText().contains("validation rule"));
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
        check("recipe: blueprint opens Setup and hands over", ImplRpa.recipe("workflows", M.createObjectNode().put("type", "blueprint").put("name", "B")).toString().contains("\"confirm\""));
        java.lang.reflect.Method cs = ImplRpa.class.getDeclaredMethod("conditions", String.class); cs.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.List<String[]> parts = (java.util.List<String[]>) cs.invoke(null, "Lead Status is 'Qualified' and PIDG Code is empty");
        check("recipe: two conditions parsed", parts.size() == 2 && "Qualified".equals(parts.get(0)[2]) && "is empty".equals(parts.get(1)[1]));
        ObjectNode vr2 = M.createObjectNode().put("module", "Leads").put("name", "Block").put("condition", "Lead Status is 'Qualified' and PIDG Code is empty").put("error_message", "Enter the PIDG code");
        String rec = ImplRpa.recipe("validation_rules", vr2).toString();
        check("recipe: deep link to the module's validation rules", rec.contains("/settings/modules/Leads/validation-rules"));
        check("recipe: second criterion added", rec.contains("Add criteria"));
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
